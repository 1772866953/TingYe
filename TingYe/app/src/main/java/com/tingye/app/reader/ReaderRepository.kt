package com.tingye.app.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.tingye.app.data.LibraryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ReaderRepository(private val context: Context, private val database: LibraryDatabase) {
    val dao = database.readerDao()
    private val root get() = File(context.filesDir, "novels").apply { mkdirs() }
    private fun directory(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return File(root, id)
    }
    suspend fun import(uri: Uri): ReadingBook = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "小说.txt"
        val id = UUID.randomUUID().toString()
        val target = directory(id).apply { mkdirs() }
        try {
            val source = File(target, "original")
            context.contentResolver.openInputStream(uri)?.use { input -> source.outputStream().use {
                it.write(NovelParser.readLimited(input))
            } } ?: error("无法读取所选文件")
            val novel = NovelParser.parse(source, name)
            val manifest = JSONArray()
            novel.sections.forEachIndexed { index, section ->
                File(target, "$index.txt").writeText(section.text)
                manifest.put(JSONObject().put("title", section.title).put("length", section.text.length))
            }
            File(target, "manifest.json").writeText(manifest.toString())
            val book = ReadingBook(id, novel.title, novel.author, novel.format, novel.sections.size,
                novel.sections.sumOf { it.text.length.toLong() })
            dao.insert(book)
            book
        } catch (error: Exception) { target.deleteRecursively(); throw error }
    }
    suspend fun chapters(id: String): List<TextChapter> = withContext(Dispatchers.IO) {
        val array = JSONArray(File(directory(id), "manifest.json").readText())
        List(array.length()) { i -> array.getJSONObject(i).let { TextChapter(it.getString("title"), it.getInt("length")) } }
    }
    suspend fun text(id: String, chapter: Int): String = withContext(Dispatchers.IO) {
        require(chapter >= 0)
        File(directory(id), "$chapter.txt").readText()
    }
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        dao.delete(id)
        directory(id).deleteRecursively()
    }
    suspend fun link(book: ReadingBook, audioBookId: Long?) {
        clearRecognitionJournal(book.id)
        database.withTransaction {
            dao.clearPoints(book.id)
            dao.clearSentences(book.id);dao.clearImport(book.id)
            val fresh = dao.book(book.id) ?: return@withTransaction
            dao.update(fresh.copy(audioBookId = audioBookId, syncEnabled = audioBookId != null, allowApproximate = false))
        }
    }
    private suspend fun clearRecognitionJournal(id: String) = withContext(Dispatchers.IO) {
        val root = directory(id)
        listOf("recognition-v1.json", "recognition-v1.json.bak", "recognition-v1.json.new", "recognition-v1.tmp").forEach { name ->
            val file = File(root, name)
            check(!file.exists() || file.delete()) { "识别记录删除失败，请重试" }
        }
    }
    suspend fun clearCorrespondence(id: String) {
        clearRecognitionJournal(id)
        database.withTransaction {
            dao.clearPoints(id)
            dao.clearSentences(id);dao.clearImport(id)
            dao.book(id)?.let { dao.update(it.copy(syncEnabled = false, allowApproximate = false)) }
        }
    }
    suspend fun mapChapter(id: String, chapter: Int, points: List<ReadingSyncPoint>) {
        database.withTransaction {
            dao.clearKind(id, chapter, "chapter"); dao.clearKind(id, chapter, "manual"); dao.clearKind(id, chapter, "subtitle")
            dao.insertPoints(points)
        }
    }
    suspend fun addCalibration(point: ReadingSyncPoint) {
        database.withTransaction {
            val retained = dao.points(point.readerId).filterNot { it.textChapter == point.textChapter && it.textOffset == point.textOffset && it.kind != "chapter" }
            dao.clearPoints(point.readerId)
            dao.insertPoints(retained.map { it.copy(id = 0) } + point)
        }
    }
    suspend fun replaceEstimates(id: String, points: List<ReadingSyncPoint>) = database.withTransaction {
        val calibrated=dao.points(id).filter { it.kind!="chapter" }
        dao.clearPoints(id)
        dao.insertPoints(calibrated.map { it.copy(id=0) }+points)
    }
    suspend fun importSync(uri:Uri,book:ReadingBook)=SentenceSyncImporter(context,database).importFile(uri,book)
    suspend fun importSubtitles(uri: Uri, book: ReadingBook, audioChapterId: Long, durationMs: Long): Int = withContext(Dispatchers.IO) {
        val source = context.contentResolver.openInputStream(uri)?.use { NovelParser.decode(NovelParser.readLimited(it, 4 * 1024 * 1024)) }
            ?: error("无法读取字幕")
        val chapters=chapters(book.id)
        val sections=chapters.indices.asSequence().map { File(directory(book.id),"$it.txt").readText() }
        val matches = SubtitleAlignment.matchSections(sections, SubtitleAlignment.parse(source))
            .filter { it.timeMs in 0..durationMs }
        require(matches.size >= 2) { "整本原文中不足两处可靠匹配，请检查字幕或手动校准" }
        val additions=matches.map {
            val location=ReadingAlignment.textLocation(it.offset,chapters)
            ReadingSyncPoint(readerId=book.id,textChapter=location.chapter,textOffset=location.offset,
                audioChapterId=audioChapterId,positionMs=it.timeMs,kind="subtitle")
        }
        database.withTransaction {
            val retained=dao.points(book.id).filterNot { it.kind=="subtitle" && it.audioChapterId==audioChapterId }
            val audio=book.audioBookId?.let { database.dao().chapters(it) }.orEmpty()
            require(additions.all { ReadingAlignment.calibrationIsOrdered(chapters,audio,retained+additions,it) }) {
                "字幕与已有校准的先后顺序冲突，请检查字幕所属音频"
            }
            dao.clearPoints(book.id)
            dao.insertPoints(retained.map { it.copy(id=0) }+additions)
        }
        matches.size
    }
}
