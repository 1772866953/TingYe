package com.tingye.app.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.tingye.app.data.LibraryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Validate the entire portable index before replacing any prior imported rows. */
class SentenceSyncImporter(private val context:Context,private val database:LibraryDatabase) {
    private fun line(input:java.io.BufferedReader):String? {
        val first=input.read();if(first<0)return null
        return buildString { var char=first
            while(char>=0 && char!=10){require(length<16384){"对应文件句子记录过长"};append(char.toChar());char=input.read()}
        }.also { require(it.isNotBlank()){"对应文件存在空白记录"} }
    }
    suspend fun importFile(uri:Uri,book:ReadingBook):ReadingSyncImport=withContext(Dispatchers.IO) {
        val audioBook=book.audioBookId ?: error("请先关联有声书")
        require(book.id.matches(Regex("[a-f0-9-]{36}")))
        val novelRoot=File(context.filesDir,"novels/${book.id}")
        val temporary=File.createTempFile("tingye-sync-",".tysync",context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input->temporary.outputStream().use { output->
                val buffer=ByteArray(32768);var total=0L
                while(true){currentCoroutineContext().ensureActive();val count=input.read(buffer);if(count<0)break
                    total+=count;require(total<=64L*1024*1024){"对应文件超过 64 MB"};output.write(buffer,0,count)}
            } } ?: error("无法读取对应文件")
            ZipFile(temporary).use { zip->
                require(zip.size()==2 && zip.getEntry("manifest.json")!=null && zip.getEntry("sentences.jsonl")!=null){"无效的听页对应文件"}
                val manifestEntry=zip.getEntry("manifest.json")
                require(manifestEntry.size in 1..4L*1024*1024){"对应文件信息无效"}
                val manifest=JSONObject(zip.getInputStream(manifestEntry).use { NovelParser.readLimited(it,4*1024*1024).toString(Charsets.UTF_8) })
                require(manifest.getString("format")=="tingye-sentence-sync" && manifest.getInt("version")==1 &&
                    manifest.getString("canonical")=="utf16-bmp-letter-digit-lower-v1"){"对应文件版本不受支持，请更新听页"}
                val source=manifest.getJSONObject("book")
                val metadata=org.json.JSONArray(File(novelRoot,"manifest.json").readText())
                val counts=IntArray(metadata.length())
                val starts=LongArray(counts.size+1)
                val digest=MessageDigest.getInstance("SHA-256")
                for(index in counts.indices){
                    currentCoroutineContext().ensureActive()
                    val canonical=SyncCanonical.text(File(novelRoot,"$index.txt").readText())
                    counts[index]=canonical.length;starts[index+1]=starts[index]+canonical.length
                    digest.update(canonical.toByteArray(Charsets.UTF_8))
                }
                require(starts.last()==source.getLong("characters") && SyncCanonical.hex(digest.digest())==source.getString("text_sha256")){
                    "对应文件与这本小说的正文不一致，请在电脑上选择同一版本的原文重新生成"
                }
                val tracks=manifest.getJSONArray("tracks")
                require(tracks.length() in 1..5000)
                val requested=linkedMapOf<String,Long>()
                val trackNames=mutableMapOf<String,String>()
                for(i in 0 until tracks.length()){
                    val track=tracks.getJSONObject(i);val id=track.getString("id")
                    require(id.matches(Regex("[a-f0-9]{64}")) && !requested.containsKey(id)) { "对应文件音频信息无效" }
                    val duration=track.getLong("duration_ms");require(duration>0)
                    requested[id]=duration;trackNames[id]=track.getString("name")
                }
                val expectedCount=manifest.getInt("sentence_count")
                require(expectedCount in 1..2000000){"对应文件没有可导入的短句，或超过 200 万条上限"}
                val entry=zip.getEntry("sentences.jsonl")
                require(entry.size in 1..384L*1024*1024){"短句索引大小无效"}
                val needed=mutableSetOf<String>()
                zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { input->
                    var count=0
                    while(true){currentCoroutineContext().ensureActive();val record=line(input) ?: break
                        needed+=JSONObject(record).getString("audio");count++
                        require(count<=expectedCount){"对应文件句子数量不一致"}}
                    require(count==expectedCount && needed.all { it in requested }){"对应文件不完整或音频信息无效"}
                }
                val wantedNames=needed.map { trackNames.getValue(it).substringBeforeLast('.') }.toSet()
                val audio=database.dao().chapters(audioBook).sortedBy { if(it.title.substringBeforeLast('.') in wantedNames)0 else 1 }
                val audioIds=mutableMapOf<String,Long>()
                for(track in audio){
                    currentCoroutineContext().ensureActive()
                    val fingerprint=runCatching { context.contentResolver.openFileDescriptor(Uri.parse(track.uri),"r")?.use { descriptor->
                        FileInputStream(descriptor.fileDescriptor).use { input->
                            val channel=input.channel
                            SyncCanonical.fingerprint(channel.size()){position,length->
                                channel.position(position)
                                val bytes=ByteArray(length);var at=0
                                while(at<length){val read=input.read(bytes,at,length-at);if(read<0)break;at+=read}
                                bytes.copyOf(at)
                            }
                        }
                    } }.getOrNull() ?: continue
                    if(fingerprint in needed){
                        require(!audioIds.containsKey(fingerprint)){"有声书包含内容重复的音频，请先移除重复章节"}
                        audioIds[fingerprint]=track.id
                        if(audioIds.size==needed.size)break
                    }
                }
                require(audioIds.isNotEmpty()){"对应文件的音频与关联有声书不一致，请确认使用同一批原始音频"}
                require(needed.all { it in audioIds }){"对应文件所需音频不完整，请确认已导入同一批原始音频"}
                var cachedChapter=-1;var cachedPositions=IntArray(0)
                fun position(chapter:Int,offset:Long):Int {
                    if(cachedChapter!=chapter){cachedChapter=chapter;cachedPositions=SyncCanonical.positions(File(novelRoot,"$chapter.txt").readText())}
                    val local=(offset-starts[chapter]).toInt()
                    return if(local>=cachedPositions.size)metadata.getJSONObject(chapter).getInt("length") else cachedPositions[local]
                }
                fun locate(offset:Long):Int {
                    var low=0;var high=counts.size
                    while(low<high){val middle=(low+high)/2;if(starts[middle]<=offset)low=middle+1 else high=middle}
                    var index=low-1
                    while(index>=0 && counts[index]==0)index--
                    require(index>=0){"原文位置无效"}
                    return index
                }
                suspend fun readRows(consume:suspend (List<ReadingSentence>)->Unit):Int {
                    var count=0;var previousEnd=-1L
                    val audioTime=mutableMapOf<String,Long>()
                    zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { input->
                        while(true){
                            currentCoroutineContext().ensureActive()
                            val record=line(input) ?: break
                            val row=JSONObject(record)
                            val start=row.getLong("n0");val end=row.getLong("n1");val id=row.getString("audio")
                            val t0=row.getLong("t0");val t1=row.getLong("t1");val confidence=row.getDouble("confidence")
                            require(start>=0 && start>=previousEnd && end>start && end<=starts.last() && id in requested &&
                                t0>=0 && t1>t0 && t1<=requested.getValue(id)+120 && confidence.isFinite() && confidence in .80..1.0 &&
                                t0>=(audioTime[id] ?: -1L)){"对应文件存在无效或倒序的句子记录"}
                            require(id in audioIds){"缺少对应音频：${trackNames[id] ?: id.take(8)}"}
                            previousEnd=end;audioTime[id]=t0;count++
                            require(count<=expectedCount){"对应文件句子数量不一致"}
                            val first=locate(start)
                            val last=locate(end-1)
                            val sentences=(first..last).filter { counts[it]>0 }.map { chapter->
                                ReadingSentence(readerId=book.id,textChapter=chapter,textStart=if(chapter==first)position(chapter,start) else 0,
                                    textEnd=if(chapter==last)position(chapter,end) else metadata.getJSONObject(chapter).getInt("length"),
                                    audioChapterId=audioIds.getValue(id),startMs=t0,endMs=t1,confidence=confidence.toFloat())
                            }
                            consume(sentences)
                        }
                    }
                    require(count==expectedCount){"对应文件不完整，原有数据未更改"}
                    return count
                }
                readRows { }
                val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                    if(it.moveToFirst())it.getString(0) else null } ?: "听读对应.tysync"
                val imported=ReadingSyncImport(book.id,name,expectedCount,manifest.getBoolean("complete"),manifest.getDouble("coverage").toFloat().coerceIn(0f,1f))
                database.withTransaction {
                    check(database.readerDao().book(book.id)?.audioBookId==audioBook){"有声书关联已更改，请重新导入"}
                    database.readerDao().clearSentences(book.id)
                    val batch=ArrayList<ReadingSentence>(400)
                    readRows { rows->batch.addAll(rows);if(batch.size>=400){database.readerDao().insertSentences(batch.toList());batch.clear()} }
                    if(batch.isNotEmpty())database.readerDao().insertSentences(batch)
                    database.readerDao().saveImport(imported)
                }
                imported
            }
        } finally { temporary.delete() }
    }
}
