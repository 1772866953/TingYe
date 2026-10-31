package com.tingye.app

import com.tingye.app.reader.*
import com.tingye.app.data.Chapter
import org.junit.Assert.*
import org.junit.Test

class ReadingMatchingTest {
    private val text=(1..4).map { TextChapter("第${it}章 故事",1000) }
    private fun tracks(last: Int)=listOf(Chapter(10,1,"小说_01_1-2","a",0,100_000),
        Chapter(11,1,"小说_02_3-$last","b",1,100_000))

    @Test fun compatibleMergedFilesUseTheirActualNovelRanges() {
        val audio=tracks(4)
        val plan=ReaderMatching.plan("r",text,audio)
        assertEquals("ranges",plan.method)
        assertEquals(2,plan.matches)
        assertEquals(AudioLocation(10,0,75_000,true),ReadingAlignment.toAudio(ReadingLocation(1,500),text,audio,plan.points,true))
        assertEquals(ReadingLocation(3,500),ReadingAlignment.toText(11,75_000,text,audio,plan.points,true))
    }
    @Test fun narrationNumberMismatchFallsBackToWholeBook() {
        val plan=ReaderMatching.plan("r",text,tracks(10))
        assertEquals("whole",plan.method)
        assertEquals(ReadingLocation(2,0),ReadingAlignment.toText(11,0,text,tracks(10),plan.points,true))
        assertNull(ReadingAlignment.toText(11,0,text,tracks(10),plan.points,false))
    }
    @Test fun chapterNumbersHandleChineseAndFullwidthDigits() {
        assertEquals(582,ReaderMatching.chapterNumber("第五百八十二章 山海"))
        assertEquals(1614,ReaderMatching.chapterNumber("第１６１４章 终章"))
        assertEquals(108,ReaderMatching.chapterNumber("第一〇八回 故事"))
        assertNull(ReaderMatching.chapterNumber("第一卷 靠山老祖"))
    }
    @Test fun estimateBoundaryKeepsTheCorrectAudioTrack() {
        val audio=tracks(4)
        val points=text.flatMapIndexed { i,t -> ReadingAlignment.chapterPoints("r",i,t,
            Chapter(i+10L,1,t.title,"uri-$i",i,100_000)) }
        val individual=text.mapIndexed { i,t -> Chapter(i+10L,1,t.title,"uri-$i",i,100_000) }
        assertEquals(AudioLocation(11,1,0,true),ReadingAlignment.toAudio(ReadingLocation(1,0),text,individual,points,true))
        val single=listOf(TextChapter("整本正文",6000))
        val whole=ReadingAlignment.wholeBookPoints("r",single,audio)
        assertEquals(AudioLocation(11,1,50_000,true),ReadingAlignment.toAudio(ReadingLocation(0,4500),single,audio,whole,true))
    }
    @Test fun wholeNovelSubtitlesCrossSectionsAndRejectDuplicates() {
        val sections=listOf("开篇。山间的晨光穿过","树叶照亮了整条小路。结尾的风轻轻吹过山林。","结尾的风轻轻吹过山林。")
        val cues=listOf(SubtitleCue(1000,"山间的晨光穿过树叶照亮了整条小路"),SubtitleCue(2000,"结尾的风轻轻吹过山林"))
        assertEquals(listOf(SubtitleMatch(3,1000)),SubtitleAlignment.matchSections(sections.asSequence(),cues))
    }
    @Test fun subtitleWindowsPreserveOffsetsAndCheckDistantDuplicates() {
        val phrase="清晨的山路还带着昨夜的雨意"
        val source="。".repeat(23)+"风".repeat(65530)+phrase+"雨".repeat(65536)
        val cues=listOf(SubtitleCue(1000,phrase))
        assertEquals(listOf(SubtitleMatch(65553,1000)),SubtitleAlignment.match(source,cues))
        assertTrue(SubtitleAlignment.match(source+phrase,cues).isEmpty())
    }
    @Test fun srtAndVttParseAndSkipChangedOrRepeatedContent() {
        val source="""WEBVTT

00:00:01.000 --> 00:00:03.000
雨停以后小镇的街道渐渐安静

00:00:04.000 --> 00:00:05.000
这是讲书人添加的一句评论

00:00:06.000 --> 00:00:08.000
他推开木门看见桌上放着一封信

00:00:09.000 --> 00:00:10.000
同样的一句话在这里出现
"""
        val story="雨停以后，小镇的街道渐渐安静。\n他推开木门，看见桌上放着一封信。\n同样的一句话在这里出现。同样的一句话在这里出现。"
        val cues=SubtitleAlignment.parse(source)
        assertEquals(4,cues.size)
        val result=SubtitleAlignment.match(story,cues)
        assertEquals(2,result.size)
        assertEquals(1000L,result[0].timeMs)
        assertEquals(story.indexOf("他推开"),result[1].offset)
        assertEquals(listOf(SubtitleCue(3723004,"书中的文字")),SubtitleAlignment.parse("1\r\n01:02:03,004 --> 01:02:04,000\r\n<i>书中的文字</i>"))
    }
}
