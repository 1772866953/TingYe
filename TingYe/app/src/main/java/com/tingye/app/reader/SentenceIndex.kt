package com.tingye.app.reader

import androidx.room.*
import com.tingye.app.data.Chapter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

@Entity(tableName="reading_sentences",indices=[Index(value=["readerId","textChapter","textStart"],unique=true),
    Index(value=["readerId","audioChapterId","startMs"]),Index("audioChapterId")],foreignKeys=[
    ForeignKey(entity=ReadingBook::class,parentColumns=["id"],childColumns=["readerId"],onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=Chapter::class,parentColumns=["id"],childColumns=["audioChapterId"],onDelete=ForeignKey.CASCADE)])
data class ReadingSentence(@PrimaryKey(autoGenerate=true) val id:Long=0,val readerId:String,
    val textChapter:Int,val textStart:Int,val textEnd:Int,val audioChapterId:Long,
    val startMs:Long,val endMs:Long,val confidence:Float)

@Entity(tableName="reading_sync_imports",foreignKeys=[ForeignKey(entity=ReadingBook::class,
    parentColumns=["id"],childColumns=["readerId"],onDelete=ForeignKey.CASCADE)])
data class ReadingSyncImport(@PrimaryKey val readerId:String,val fileName:String,val sentenceCount:Int,
    val complete:Boolean,val coverage:Float,val importedAt:Long=System.currentTimeMillis())

object SentenceAlignment {
    fun toAudio(location:ReadingLocation,audio:List<Chapter>,sentences:List<ReadingSentence>,visibleEnd:Int?=null):AudioLocation? {
        val sentence=sentences.firstOrNull { it.textChapter==location.chapter && location.offset>=it.textStart && location.offset<it.textEnd }
            // A page can begin with an unmatched heading or sentence. Use a real
            // indexed sentence on this page only; never jump to a distant chapter.
            ?: visibleEnd?.let { end->sentences.firstOrNull { it.textChapter==location.chapter && it.textStart>=location.offset && it.textStart<end } }
            ?: return null
        val index=audio.indexOfFirst { it.id==sentence.audioChapterId }.takeIf { it>=0 } ?: return null
        return AudioLocation(sentence.audioChapterId,index,(sentence.startMs-120).coerceAtLeast(0),false)
    }
    fun inSpeechGap(position:Long,before:ReadingSentence?,after:ReadingSentence?):ReadingSentence? {
        if(before==null || after==null || before.audioChapterId!=after.audioChapterId ||
            before.textChapter!=after.textChapter || before.textEnd!=after.textStart ||
            position<before.endMs || position>after.startMs || after.startMs-before.endMs !in 0..3000)return null
        return if(position-before.endMs<=after.startMs-position)before else after
    }
}

/** Shared with desktop canonical-v1. Offsets are normalized BMP UTF-16 letters/digits. */
object SyncCanonical {
    fun text(value:String)=buildString { value.forEach { if(it.isLetterOrDigit())append(it.lowercaseChar()) } }
    fun positions(value:String):IntArray {
        val output=IntArray(value.length)
        var count=0
        value.forEachIndexed { index,char->if(char.isLetterOrDigit())output[count++]=index }
        return output.copyOf(count)
    }
    fun hex(bytes:ByteArray)=buildString(bytes.size*2) {
        val digits="0123456789abcdef"
        bytes.forEach { val value=it.toInt() and 255;append(digits[value ushr 4]);append(digits[value and 15]) }
    }
    fun fingerprint(size:Long,read:(Long,Int)->ByteArray):String {
        require(size>=0)
        val digest=MessageDigest.getInstance("SHA-256")
        digest.update("tingye-audio-v1\n$size\n".toByteArray(Charsets.US_ASCII))
        listOf(0L,((size-65536)/2).coerceAtLeast(0),(size-65536).coerceAtLeast(0)).distinct().sorted().forEach { position->
            val data=read(position,minOf(65536L,size-position).toInt())
            require(data.size==minOf(65536L,size-position).toInt()) { "音频文件读取不完整" }
            digest.update(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(position).putInt(data.size).array())
            digest.update(data)
        }
        return hex(digest.digest())
    }
}
