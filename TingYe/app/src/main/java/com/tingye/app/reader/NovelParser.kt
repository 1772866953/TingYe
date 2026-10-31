package com.tingye.app.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

data class NovelSection(val title: String, val text: String)
data class ParsedNovel(val title: String, val author: String, val format: String, val sections: List<NovelSection>)

/** Text novels are normalized once at import; character offsets survive font/page changes. */
object NovelParser {
    private const val MAX_INPUT = 64 * 1024 * 1024
    private const val MAX_ENTRY = 8 * 1024 * 1024
    private const val MAX_CHAPTER = 60_000
    private val heading = Regex("^(?:第[零〇一二三四五六七八九十百千万两0-9]+[章节回卷部篇].{0,55}|(?:Chapter|CHAPTER)\\s+\\d+.{0,55}|[序楔][章子]|(?:前言|后记|尾声|番外).{0,25})$")

    fun readLimited(input: InputStream, limit: Int = MAX_INPUT): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val count = input.read(buffer)
            if (count < 0) break
            require(result.size().toLong() + count <= limit) { "文件过大，单本上限 64 MB，EPUB 单个正文上限 8 MB" }
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
        }
        return runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { bytes.toString(charset("GB18030")) }.removePrefix("\uFEFF")
    }

    fun parse(file: File, name: String): ParsedNovel {
        val extension = name.substringAfterLast('.', "txt").lowercase()
        if (extension == "epub" || file.inputStream().use { it.read() == 0x50 && it.read() == 0x4B }) return epub(file, name.substringBeforeLast('.'))
        require(extension in listOf("txt", "text", "md", "markdown", "html", "htm", "xhtml")) { "支持 TXT、EPUB、HTML 和 Markdown；其他格式请先转换" }
        val source = decode(file.inputStream().use { readLimited(it) })
        val text = if (extension in listOf("html", "htm", "xhtml")) htmlText(Jsoup.parse(source).body()) else source
        val sections = splitText(text)
        require(sections.isNotEmpty()) { "文件中没有可阅读的正文" }
        return ParsedNovel(name.substringBeforeLast('.'), "", extension.uppercase(), sections)
    }

    fun splitText(source: String): List<NovelSection> {
        val text = source.replace("\r\n", "\n").replace('\r', '\n').replace('\u0000', ' ').trim()
        val chapters = mutableListOf<NovelSection>()
        var title = "正文"
        val body = StringBuilder()
        fun flush() {
            if (body.isNotBlank()) chapters += splitLong(title, body.toString().trim())
            body.setLength(0)
        }
        for (line in text.lineSequence()) {
            val candidate = line.trim().removePrefix("# ").removePrefix("## ").trim()
            if (heading.matches(candidate)) { flush(); title = candidate }
            else body.append(line.trimEnd()).append('\n')
        }
        flush()
        return chapters
    }

    private fun splitLong(title: String, text: String): List<NovelSection> {
        if (text.length <= MAX_CHAPTER) return listOf(NovelSection(title, text))
        val parts = mutableListOf<NovelSection>()
        var start = 0
        while (start < text.length) {
            var end = (start + MAX_CHAPTER).coerceAtMost(text.length)
            if (end < text.length) {
                val newline = text.lastIndexOf('\n', end)
                if (newline > start + MAX_CHAPTER / 2) end = newline + 1
                if (end > 0 && Character.isHighSurrogate(text[end-1])) end--
            }
            parts += NovelSection(if (parts.isEmpty()) title else "$title · 续 ${parts.size}", text.substring(start, end))
            start = end
        }
        return parts
    }

    private fun htmlText(root: Element): String {
        root.select("script,style,nav,rt,rp,svg,audio,video").remove()
        val out = StringBuilder()
        fun visit(node: org.jsoup.nodes.Node) {
            when (node) {
                is TextNode -> out.append(node.wholeText.replace(Regex("[\\t\\r\\n ]+"), " "))
                is Element -> {
                    val block = node.tagName() in setOf("p", "div", "section", "h1", "h2", "h3", "h4", "li", "blockquote", "br", "hr")
                    if (block && out.isNotEmpty() && out.last() != '\n') out.append('\n')
                    node.childNodes().forEach(::visit)
                    if (block && out.isNotEmpty() && out.last() != '\n') out.append('\n')
                }
            }
        }
        visit(root)
        return out.lines().map { it.trim().replace('\u00A0', ' ') }.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /** Resolve inside the archive; never extract paths or fetch external resources. */
    internal fun zipPath(base: String, href: String): String {
        require(!href.startsWith('/') && !Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(href)) { "EPUB 正文引用无效" }
        val decoded = URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8")
        val path = URI(null, null, "/" + base.substringBeforeLast('/', "") + "/" + decoded, null).normalize().path.trimStart('/')
        require(!path.startsWith("../") && !decoded.contains('\\')) { "EPUB 路径无效" }
        return path
    }

    private fun epub(file: File, fallback: String): ParsedNovel = ZipFile(file).use { zip ->
        require(zip.size() <= 20_000) { "EPUB 目录过大" }
        var expanded = 0L
        fun read(path: String): String {
            val entry = zip.getEntry(path) ?: error("EPUB 缺少正文资源：$path")
            require(entry.size <= MAX_ENTRY) { "EPUB 单个正文过大" }
            val bytes = zip.getInputStream(entry).use { readLimited(it, MAX_ENTRY) }
            expanded += bytes.size
            require(expanded <= MAX_INPUT) { "EPUB 正文总量超过 64 MB" }
            return decode(bytes)
        }
        val container = Jsoup.parse(read("META-INF/container.xml"), "", Parser.xmlParser())
        val opfPath = container.allElements.firstOrNull { it.tagName().substringAfter(':') == "rootfile" }?.attr("full-path") ?: error("无效的 EPUB 目录")
        val opf = Jsoup.parse(read(opfPath), "", Parser.xmlParser())
        fun elements(tag: String) = opf.allElements.filter { it.tagName().substringAfter(':') == tag }
        fun metadata(tag: String) = opf.allElements.firstOrNull { it.tagName().substringAfter(':') == tag }?.text().orEmpty()
        require(opf.select("meta[property=rendition:layout]").none { it.text() == "pre-paginated" }) { "暂不支持固定版式 EPUB，请使用可重排的小说版本" }
        val items = elements("item").associateBy { it.attr("id") }
        val sections = mutableListOf<NovelSection>()
        for (itemref in elements("itemref")) {
            if (itemref.attr("linear") == "no") continue
            val item = items[itemref.attr("idref")] ?: continue
            if (item.attr("media-type") !in listOf("application/xhtml+xml", "text/html")) continue
            if (item.attr("properties").split(' ').contains("nav")) continue
            val doc = Jsoup.parse(read(zipPath(opfPath, item.attr("href"))))
            val title = doc.selectFirst("h1,h2,h3")?.text()?.takeIf { it.isNotBlank() }
                ?: doc.title().takeIf { it.isNotBlank() } ?: "第 ${sections.size + 1} 节"
            doc.select("h1,h2,h3").firstOrNull()?.takeIf { it.text() == title }?.remove()
            val text = htmlText(doc.body())
            if (text.isNotBlank()) sections += splitLong(title, text)
        }
        require(sections.isNotEmpty()) { "EPUB 没有可阅读的文字正文，可能是图片书或受保护的文件" }
        ParsedNovel(metadata("title").ifBlank { fallback }, metadata("creator"), "EPUB", sections)
    }
}
