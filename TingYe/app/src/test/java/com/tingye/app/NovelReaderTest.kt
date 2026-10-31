package com.tingye.app

import com.tingye.app.reader.*
import com.tingye.app.data.Chapter
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NovelReaderTest {
    private val text = listOf(TextChapter("第一章 风来", 1000),TextChapter("第二章 夜归",1000))
    private val audio = listOf(Chapter(10,1,"第一集 风来","a",0,100_000),Chapter(11,1,"第二集 夜归","b",1,100_000))
    private fun point(chapter:Int,offset:Int,audioId:Long,time:Long,kind:String="manual") =
        ReadingSyncPoint(readerId="reader",textChapter=chapter,textOffset=offset,audioChapterId=audioId,positionMs=time,kind=kind)

    @Test fun suppliedLongEpubStillImportsAllSections() {
        // Optional large-book fixture; no copyrighted novel is distributed.
        val file=File(System.getenv("TINGYE_TEST_EPUB") ?: "missing-test-fixture.epub")
        org.junit.Assume.assumeTrue(file.isFile)
        val novel=NovelParser.parse(file,file.name)
        assertTrue(novel.title.isNotBlank())
        assertTrue(novel.sections.size>1500)
        assertTrue(novel.sections.all { it.text.isNotBlank() })
    }

    @Test fun textEncodingAndChapterDetection() {
        val source="序章\r\n冬天来了。\r\n第一章 风来\r\n小镇下了一夜的雨。\r\n第二章 夜归\r\n他终于回到了家。"
        for(charset in listOf(Charsets.UTF_8,charset("GB18030"))) {
            val decoded=NovelParser.decode(source.toByteArray(charset))
            val chapters=NovelParser.splitText(decoded)
            assertEquals(3,chapters.size);assertEquals("第二章 夜归",chapters.last().title)
            assertTrue(chapters.last().text.contains("回到了家"))
        }
        assertEquals("书页",NovelParser.decode(byteArrayOf(0xFF.toByte(),0xFE.toByte())+"书页".toByteArray(Charsets.UTF_16LE)))
    }
    @Test fun longTextIsSplitWithoutLosingCharacters() {
        val source="山海之间。".repeat(40_000)
        val result=NovelParser.splitText(source)
        assertTrue(result.size>1);assertEquals(source,result.joinToString(""){it.text})
        assertTrue(result.all{it.text.length<=60_000})
    }
    @Test fun epubUsesSpineOrderAndResolvesEscapedPaths() {
        val file=File.createTempFile("reader-test", ".epub")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(name:String,source:String){zip.putNextEntry(ZipEntry(name));zip.write(source.toByteArray());zip.closeEntry()}
                entry("META-INF/container.xml","<container><rootfiles><rootfile full-path='OPS/package.opf'/></rootfiles></container>")
                entry("OPS/package.opf","""<opf:package xmlns:opf="urn:opf" xmlns:dc="urn:dc"><opf:metadata><dc:title>山海之间</dc:title><dc:creator>作者</dc:creator></opf:metadata><opf:manifest><opf:item id="b" href="Text/second.xhtml" media-type="application/xhtml+xml"/><opf:item id="a" href="Text/first%20part.xhtml" media-type="application/xhtml+xml"/></opf:manifest><opf:spine><opf:itemref idref="a"/><opf:itemref idref="b"/></opf:spine></opf:package>""")
                entry("OPS/Text/first part.xhtml","<html><body><h1>第一章</h1><p>山间&amp;晨光</p><script>bad()</script><p>下一段。</p></body></html>")
                entry("OPS/Text/second.xhtml","<html><body><h1>第二章</h1><p>故事继续。</p></body></html>")
            }
            val novel=NovelParser.parse(file,"novel.epub")
            assertEquals("山海之间",novel.title);assertEquals("作者",novel.author)
            assertEquals(listOf("第一章","第二章"),novel.sections.map{it.title})
            assertTrue(novel.sections[0].text.contains("山间&晨光"));assertFalse(novel.sections[0].text.contains("bad"))
        } finally {file.delete()}
    }
    @Test fun calibrationsInterpolateButDoNotExtrapolate() {
        val points=listOf(point(0,100,10,20_000),point(0,900,10,80_000))
        assertNull(ReadingAlignment.toAudio(ReadingLocation(0,0),text,audio,points))
        assertNull(ReadingAlignment.toAudio(ReadingLocation(0,950),text,audio,points))
        assertEquals(50_000L,ReadingAlignment.toAudio(ReadingLocation(0,500),text,audio,points)!!.positionMs)
        assertEquals(ReadingLocation(0,500),ReadingAlignment.toText(10,50_000,text,audio,points))
    }
    @Test fun oneEpisodeCanCoverTwoTextChapters() {
        val points=listOf(point(0,500,10,20_000),point(1,500,10,80_000))
        assertEquals(50_000L,ReadingAlignment.toAudio(ReadingLocation(1,0),text,audio,points)!!.positionMs)
        assertEquals(ReadingLocation(1,0),ReadingAlignment.toText(10,50_000,text,audio,points))
    }
    @Test fun calibrationCanCrossBothKindsOfChapterBoundary() {
        val points=listOf(point(0,500,10,50_000),point(1,500,11,50_000))
        assertEquals(AudioLocation(11,1,0,true),ReadingAlignment.toAudio(ReadingLocation(1,0),text,audio,points))
        assertEquals(ReadingLocation(1,0),ReadingAlignment.toText(11,0,text,audio,points))
        assertTrue(ReadingAlignment.calibrationIsOrdered(text,audio,points,point(1,100,11,10_000)))
    }
    @Test fun severalAudioEpisodesMayMapToOneTextChapter() {
        val points=listOf(point(0,100,10,50_000),point(0,900,11,50_000))
        assertEquals(AudioLocation(11,1,0,true),ReadingAlignment.toAudio(ReadingLocation(0,500),text,audio,points))
        assertEquals(ReadingLocation(0,500),ReadingAlignment.toText(11,0,text,audio,points))
    }
    @Test fun missingDurationElsewhereDoesNotDisableKnownIntervals() {
        val tracks=audio+Chapter(12,1,"未读取","c",2,null)
        val points=listOf(point(0,100,10,20_000),point(0,900,10,80_000))
        assertEquals(50_000L,ReadingAlignment.toAudio(ReadingLocation(0,500),text,tracks,points)!!.positionMs)
        assertEquals(ReadingLocation(0,500),ReadingAlignment.toText(10,50_000,text,tracks,points))
        val gap=listOf(audio[0],Chapter(12,1,"未读取","c",1,null),audio[1])
        assertNull(ReadingAlignment.toAudio(ReadingLocation(0,500),text,gap,listOf(point(0,100,10,20_000),point(0,900,11,80_000))))
    }
    @Test fun audioRoundTripDoesNotMoveOneCharacterIntoPreviousPage() {
        val chapters=listOf(TextChapter("正文",7369))
        val tracks=listOf(Chapter(10,1,"正文","a",0,120_000))
        val points=listOf(point(0,0,10,0),point(0,chapters[0].length,10,120_000))
        val location=ReadingLocation(0,2697)
        val mapped=ReadingAlignment.toAudio(location,chapters,tracks,points)!!
        assertEquals(location,ReadingAlignment.toText(mapped.chapterId,mapped.positionMs,chapters,tracks,points))
    }
    @Test fun reversedOrMissingAudioCannotProduceASeek() {
        val points=listOf(point(0,100,10,80_000),point(0,900,10,20_000))
        assertNull(ReadingAlignment.toAudio(ReadingLocation(0,500),text,audio,points))
        assertNull(ReadingAlignment.toAudio(ReadingLocation(0,500),text,audio.drop(1),points))
    }

    @Test fun estimatesRequireOptInWhileSubtitlesRemainUsable() {
        val estimates=listOf(point(0,100,10,20_000,"chapter"),point(0,900,10,80_000,"chapter"))
        assertNull(ReadingAlignment.toAudio(ReadingLocation(0,500),text,audio,estimates,false))
        assertNull(ReadingAlignment.toText(10,50_000,text,audio,estimates,false))
        assertEquals(50_000L,ReadingAlignment.toAudio(ReadingLocation(0,500),text,audio,estimates,true)!!.positionMs)
        assertEquals(ReadingLocation(0,500),ReadingAlignment.toText(10,50_000,text,audio,estimates,true))
        val subtitles=estimates.map { it.copy(kind="subtitle") }
        assertEquals(50_000L,ReadingAlignment.toAudio(ReadingLocation(0,500),text,audio,subtitles,false)!!.positionMs)
        assertEquals(ReadingLocation(0,500),ReadingAlignment.toText(10,50_000,text,audio,subtitles,false))
    }
    @Test fun recognitionAnchorsStillInterpolateAcrossNovelChapters() {
        val points=listOf(point(0,500,10,20_000,"recognition"),point(1,500,10,80_000,"recognition"))
        assertEquals(50_000L,ReadingAlignment.toAudio(ReadingLocation(1,0),text,audio,points)!!.positionMs)
        assertEquals(ReadingLocation(1,0),ReadingAlignment.toText(10,50_000,text,audio,points))
    }
}
