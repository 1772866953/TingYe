package com.tingye.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.tingye.app.data.Book
import com.tingye.app.data.Chapter
import com.tingye.app.reader.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** Runs only with -Ptingye.syncQa=true, under a separate app ID. Uses the real
 * EPUB and complete desktop index; no production test hooks or phone writes. */
class SyncRegressionInstrumentation:Instrumentation(){
    private val checks=mutableListOf<String>()
    private val fixture=File("/data/local/tmp/tingye-sync-qa")
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){Thread {
        val result=Bundle()
        try{runBlocking { verify() };result.putString("stream","PASS: "+checks.joinToString("; "));finish(Activity.RESULT_OK,result)}
        catch(error:Throwable){result.putString("stream",error.stackTraceToString());finish(Activity.RESULT_CANCELED,result)}
    }.start()}
    private suspend fun main(block:()->Unit)=withContext(Dispatchers.Main){block()}
    private suspend fun eventually(label:String,timeout:Long=12000,predicate:()->Boolean){
        val deadline=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<deadline){if(withContext(Dispatchers.Main){predicate()})return;delay(50)}
        error("Timeout: $label")
    }
    private fun canvas(root:View):ReaderCanvas? {
        if(root is ReaderCanvas && root.isShown)return root
        if(root is ViewGroup)for(i in 0 until root.childCount)canvas(root.getChildAt(i))?.let{return it}
        return null
    }
    private suspend fun verify(){
        val app=targetContext.applicationContext as TingYeApp
        check(app.packageName.endsWith(".syncqa")){"Refusing to modify a production app"}
        withContext(Dispatchers.IO){app.database.clearAllTables()}
        val specification=JSONObject(File(fixture,"fixture.json").readText())
        val tracks=specification.getJSONArray("tracks")
        val audioRoot=File(app.filesDir,"sync-fixture-audio").apply{mkdirs()}
        val bookId=app.library.dao.addBook(Book(title="听读同步回归样本"))
        val audio=mutableListOf<Chapter>()
        withContext(Dispatchers.IO){
            RandomAccessFile(File(fixture,"segments.bin"),"r").use { bundle->
                for(i in 0 until tracks.length()){
                    val t=tracks.getJSONObject(i);val file=File(audioRoot,"$i.m4a")
                    if(i==0)File(fixture,"first.m4a").copyTo(file,overwrite=true)
                    else RandomAccessFile(file,"rw").use { output->
                        output.setLength(t.getLong("size"));val ranges=t.getJSONArray("ranges")
                        for(j in 0 until ranges.length()){
                            val range=ranges.getJSONObject(j);val bytes=ByteArray(range.getInt("length"))
                            bundle.seek(range.getLong("bundleOffset"));bundle.readFully(bytes)
                            output.seek(range.getLong("offset"));output.write(bytes)
                        }
                    }
                    val chapter=Chapter(bookId=bookId,title=t.getString("name"),uri=Uri.fromFile(file).toString(),orderIndex=i,durationMs=t.getLong("duration_ms"))
                    val id=app.library.dao.addChapter(chapter);audio+=chapter.copy(id=id)
                }
            }
        }
        val repository=ReaderRepository(app,app.database)
        val importedBook=repository.import(Uri.fromFile(File(fixture,"novel.epub")))
        repository.link(importedBook,bookId)
        val linked=repository.dao.book(importedBook.id)!!
        val imported=SentenceSyncImporter(app,app.database).importFile(Uri.fromFile(File(fixture,"index.tysync")),linked)
        check(imported.sentenceCount==specification.getInt("sentences"))
        val chapters=repository.chapters(linked.id)
        val all=mutableListOf<ReadingSentence>();var verifiedRows=0
        // Validate a large punctuation index one chapter at a time, just as the
        // reader does. Do not keep half a million Room objects in the test heap.
        for(chapter in chapters.indices){
            val rows=repository.dao.sentences(linked.id,chapter,chapter);verifiedRows+=rows.size
            for(row in rows){
                val offset=row.textStart+(row.textEnd-row.textStart-1)/2
                val target=SentenceAlignment.toAudio(ReadingLocation(chapter,offset),audio,rows)
                check(target?.chapterId==row.audioChapterId && target.positionMs==(row.startMs-120).coerceAtLeast(0))
            }
            all+=rows.filter { it.audioChapterId==audio[0].id }
        }
        check(verifiedRows>=imported.sentenceCount)
        checks+="full EPUB import: ${imported.sentenceCount} units; $verifiedRows raw interval lookups"
        var gaps=0
        for((a,b) in all.zipWithNext()){
            if(gaps>=100)break
            if(a.textChapter==b.textChapter && a.textEnd==b.textStart && a.audioChapterId==b.audioChapterId && b.startMs-a.endMs in 1..3000){
                val match=repository.dao.sentenceNearAudio(linked.id,a.audioChapterId,(a.endMs+b.startMs)/2)
                check(match?.id==a.id || match?.id==b.id);gaps++
            }
        }
        check(gaps>20);checks+="$gaps real sentence pauses resolve"
        val activity=startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var reader:ReaderViewModel;lateinit var player:MainViewModel
        main { reader=ViewModelProvider(activity)[ReaderViewModel::class.java];player=ViewModelProvider(activity)[MainViewModel::class.java] }
        eventually("controller ready"){player.playerUi.value.ready && reader.onAudioSync!=null}
        main { reader.open(linked.id) }
        eventually("reader layout",20000){reader.session.value?.visibleEnd!=null && canvas(activity.window.decorView)!=null}
        val sample=all.first { it.audioChapterId==audio[0].id && it.textStart>300 }
        main { reader.goTo(sample.textChapter,sample.textStart) }
        eventually("exact anchor preserved on a typeset page"){reader.session.value?.location==ReadingLocation(sample.textChapter,sample.textStart) && reader.session.value?.visibleEnd!=null}
        delay(800)
        check(reader.session.value!!.location==ReadingLocation(sample.textChapter,sample.textStart)){"Passive page layout displaced the exact sentence anchor"}
        main { check(canvas(activity.window.decorView)!!.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null)) }
        eventually("page turn settled and audio sought",20000){
            val current=reader.session.value;val target=reader.audioTarget();val playback=player.playerUi.value
            current!=null && current.location.offset!=sample.textStart && target!=null && !playback.isPlaying && playback.chapterId==target.chapterId && kotlin.math.abs(playback.position-target.positionMs)<250
        }
        val page=reader.session.value!!.location;delay(1000)
        check(reader.session.value!!.location==page){"Seek acknowledgement fed back into reader navigation"}
        checks+="real page turn seeks paused Media3 queue without jumping back"
        main { reader.close { } }
        eventually("reader closed"){reader.session.value==null}
        val later=all.first { it.audioChapterId==audio[0].id && it.startMs>sample.endMs+20000 && it.endMs-it.startMs>2500 }
        main { player.playBook(bookId,0,true,later.startMs+300) }
        eventually("real audio playing"){player.playerUi.value.isPlaying}
        delay(150);main { player.toggle() }
        eventually("real audio paused"){!player.playerUi.value.isPlaying}
        val expected=repository.dao.sentenceNearAudio(linked.id,audio[0].id,player.playerUi.value.position+120)!!
        val deadline=System.currentTimeMillis()+10000
        var saved=repository.dao.book(linked.id)!!
        while(System.currentTimeMillis()<deadline && (saved.chapterIndex!=expected.textChapter || saved.offset!=expected.textStart)){
            delay(50);saved=repository.dao.book(linked.id)!!
        }
        check(saved.chapterIndex==expected.textChapter && saved.offset==expected.textStart){"Paused audio did not update closed reader progress"}
        checks+="audio pause updates reading progress while reader screen is closed"
        main { reader.open(linked.id,player.playerUi.value) }
        eventually("reopen at paused audio sentence"){reader.session.value?.visibleEnd!=null && reader.session.value?.location==ReadingLocation(expected.textChapter,expected.textStart)}
        delay(600);check(reader.session.value!!.location==ReadingLocation(expected.textChapter,expected.textStart))
        checks+="reopened reader keeps the matching sentence after page layout"
        main { reader.setFollowing(true);player.playBook(bookId,0,true,later.startMs+400) }
        eventually("playing with reader open"){player.playerUi.value.isPlaying}
        main { check(canvas(activity.window.decorView)!!.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null)) }
        eventually("reading gesture takes priority over playing ticks",20000){
            val target=reader.audioTarget();val playback=player.playerUi.value
            target!=null && !playback.isPlaying && playback.chapterId==target.chapterId && kotlin.math.abs(playback.position-target.positionMs)<250
        }
        val newPage=reader.session.value!!.location;delay(800);check(reader.session.value!!.location==newPage)
        checks+="page turn during active following pauses audio and does not bounce back"
        main { reader.syncOptions(false,false) }
        eventually("link disabled"){reader.session.value?.book?.syncEnabled==false}
        main { reader.close { } };eventually("disabled reader closed"){reader.session.value==null}
        val unchanged=repository.dao.book(linked.id)!!
        main { player.playBook(bookId,0,true,later.startMs+800) }
        eventually("playing with link disabled"){player.playerUi.value.isPlaying};main{player.toggle()};delay(700)
        val disabled=repository.dao.book(linked.id)!!
        check(disabled.chapterIndex==unchanged.chapterIndex && disabled.offset==unchanged.offset)
        checks+="disabled progress link leaves reading position unchanged"
        // Leave the isolated demo reader linked and visible for the screenshot.
        repository.dao.update(disabled.copy(syncEnabled=true))
        main { reader.open(linked.id,player.playerUi.value) }
        eventually("final reader visible"){reader.session.value?.visibleEnd!=null}
        val output=JSONObject().put("pass",true).put("sentenceCount",imported.sentenceCount).put("coverage",imported.coverage)
            .put("checks",org.json.JSONArray(checks))
        File(app.filesDir,"sync-regression.json").writeText(output.toString(2))
        uiAutomation.takeScreenshot()?.let { image->File(app.filesDir,"sync-regression.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
    }
}
