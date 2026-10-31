package com.tingye.app

import com.tingye.app.reader.*
import com.tingye.app.data.Chapter
import org.junit.Assert.*
import org.junit.Test

class SentenceIndexTest {
    private val audio=listOf(Chapter(10,1,"音频","uri",0,100000))
    private val sentence=ReadingSentence(readerId="r",textChapter=0,textStart=100,textEnd=180,
        audioChapterId=10,startMs=20000,endMs=45000,confidence=.98f)
    @Test fun anyPositionInsideTheSentenceSeeksToTheSentenceStart() {
        for(offset in listOf(100,120,179))assertEquals(19880L,
            SentenceAlignment.toAudio(ReadingLocation(0,offset),audio,listOf(sentence))!!.positionMs)
        assertFalse(SentenceAlignment.toAudio(ReadingLocation(0,140),audio,listOf(sentence))!!.approximate)
    }
    @Test fun unmatchedTextAndWrongAudioAreNotGuessed() {
        assertNull(SentenceAlignment.toAudio(ReadingLocation(0,99),audio,listOf(sentence)))
        assertNull(SentenceAlignment.toAudio(ReadingLocation(0,180),audio,listOf(sentence)))
        assertNull(SentenceAlignment.toAudio(ReadingLocation(1,130),audio,listOf(sentence)))
        assertNull(SentenceAlignment.toAudio(ReadingLocation(0,130),emptyList(),listOf(sentence)))
    }
    @Test fun pageHeadingsUseOnlyAConfirmedSentenceVisibleOnThatPage(){
        assertEquals(19880L,SentenceAlignment.toAudio(ReadingLocation(0,20),audio,listOf(sentence),181)!!.positionMs)
        assertNull(SentenceAlignment.toAudio(ReadingLocation(0,20),audio,listOf(sentence),100))
        assertNull(SentenceAlignment.toAudio(ReadingLocation(1,20),audio,listOf(sentence),181))
    }
    @Test fun shortSilenceBetweenAdjacentTextCanResolveButMissingSentencesCannot(){
        val next=sentence.copy(textStart=180,textEnd=220,startMs=47000,endMs=49000)
        assertEquals(sentence,SentenceAlignment.inSpeechGap(45500,sentence,next))
        assertEquals(next,SentenceAlignment.inSpeechGap(46500,sentence,next))
        assertNull(SentenceAlignment.inSpeechGap(45500,sentence,next.copy(textStart=181)))
        assertNull(SentenceAlignment.inSpeechGap(45500,sentence,next.copy(startMs=50000)))
        assertNull(SentenceAlignment.inSpeechGap(45500,null,next))
    }
    @Test fun acknowledgingReaderSeeksDoesNotCreateReverseSynchronization(){
        val gate=ReadingSyncGate()
        gate.readingStarted();assertFalse(gate.acceptsAudio(1,10,1000,false))
        val before=gate.revision
        gate.sentToAudio(1,AudioLocation(10,0,19880,false));assertTrue(gate.revision>before)
        assertFalse(gate.acceptsAudio(1,10,1000,false)) // delayed old player snapshot
        assertFalse(gate.acceptsAudio(1,10,19880,false))
        assertFalse(gate.acceptsAudio(1,10,19880,false)) // duplicate seek acknowledgement
        assertTrue(gate.acceptsAudio(1,10,60000,false)) // later explicit audio navigation
        gate.readingStarted();assertFalse(gate.acceptsAudio(1,10,60100,true)) // an old playing tick cannot cancel a gesture
        gate.reset();assertTrue(gate.acceptsAudio(1,10,60100,true))
        assertTrue(gate.acceptsAudio(1,10,61000,false))
    }
    @Test fun canonicalizationPreservesAndroidOffsetsAndIgnoresFormatting() {
        val value="Ａ B12，İ\uD840\uDC00\uD83D\uDE00"
        assertEquals("ａb12i",SyncCanonical.text(value))
        assertArrayEquals(intArrayOf(0,2,3,4,6),SyncCanonical.positions(value))
    }
    @Test fun audioFingerprintSurvivesRenamesAndDetectsChangedContent() {
        val bytes=ByteArray(256000){(it%256).toByte()}
        fun hash()=SyncCanonical.fingerprint(bytes.size.toLong()){position,length->bytes.copyOfRange(position.toInt(),position.toInt()+length)}
        val original=hash()
        assertEquals("28de014889e9a2f3c55df030575e423971e648a5a89c805b3f5499ecea9a074a",original)
        assertEquals(original,hash())
        bytes[0]=99
        assertNotEquals(original,hash())
    }
    @Test fun realNovelCanonicalDigestMatchesTheDesktopParser() {
        val file=java.io.File(System.getenv("TINGYE_TEST_EPUB") ?: "missing-test-fixture.epub")
        org.junit.Assume.assumeTrue(file.isFile)
        val book=NovelParser.parse(file,file.name)
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        var count=0
        book.sections.forEach { val value=SyncCanonical.text(it.text);count+=value.length;digest.update(value.toByteArray(Charsets.UTF_8)) }
        val expectedCount=System.getenv("TINGYE_TEST_EPUB_CANONICAL_LENGTH")
        val expectedDigest=System.getenv("TINGYE_TEST_EPUB_CANONICAL_SHA256")
        org.junit.Assume.assumeTrue(expectedCount!=null && expectedDigest!=null)
        assertEquals(expectedCount!!.toInt(),count)
        assertEquals(expectedDigest,SyncCanonical.hex(digest.digest()))
    }
}
