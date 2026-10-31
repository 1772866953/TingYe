package com.tingye.app.reader

import androidx.room.*
import com.tingye.app.data.Book
import com.tingye.app.data.Chapter
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToLong

@Entity(tableName = "reading_books", indices = [Index("audioBookId")], foreignKeys = [ForeignKey(
    entity = Book::class, parentColumns = ["id"], childColumns = ["audioBookId"], onDelete = ForeignKey.SET_NULL)])
data class ReadingBook(@PrimaryKey val id: String, val title: String, val author: String,
    val format: String, val chapterCount: Int, val characterCount: Long,
    val chapterIndex: Int = 0, val offset: Int = 0, val updatedAt: Long = System.currentTimeMillis(),
    val audioBookId: Long? = null, val syncEnabled: Boolean = false, val allowApproximate: Boolean = false)

@Entity(tableName = "reading_sync", indices = [Index("readerId"), Index("audioChapterId")], foreignKeys = [
    ForeignKey(entity = ReadingBook::class, parentColumns = ["id"], childColumns = ["readerId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Chapter::class, parentColumns = ["id"], childColumns = ["audioChapterId"], onDelete = ForeignKey.CASCADE)])
data class ReadingSyncPoint(@PrimaryKey(autoGenerate = true) val id: Long = 0, val readerId: String,
    val textChapter: Int, val textOffset: Int, val audioChapterId: Long, val positionMs: Long,
    val kind: String = "manual")

@Dao
interface ReaderDao {
    @Query("SELECT * FROM reading_books ORDER BY updatedAt DESC") fun books(): Flow<List<ReadingBook>>
    @Query("SELECT * FROM reading_books WHERE id=:id") suspend fun book(id: String): ReadingBook?
    @Query("SELECT * FROM reading_books WHERE audioBookId=:audio AND syncEnabled=1") suspend fun linkedBooks(audio:Long):List<ReadingBook>
    @Insert suspend fun insert(book: ReadingBook)
    @Update suspend fun update(book: ReadingBook)
    @Query("UPDATE reading_books SET chapterIndex=:chapter, offset=:offset, updatedAt=:time WHERE id=:id")
    suspend fun progress(id: String, chapter: Int, offset: Int, time: Long = System.currentTimeMillis())
    @Query("DELETE FROM reading_books WHERE id=:id") suspend fun delete(id: String)
    @Query("SELECT * FROM reading_sync WHERE readerId=:id ORDER BY textChapter,textOffset")
    suspend fun points(id: String): List<ReadingSyncPoint>
    @Query("SELECT * FROM reading_sync WHERE readerId=:id ORDER BY textChapter,textOffset")
    fun pointsFlow(id: String): Flow<List<ReadingSyncPoint>>
    @Insert suspend fun insertSentences(sentences: List<ReadingSentence>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveImport(value:ReadingSyncImport)
    @Query("SELECT * FROM reading_sync_imports WHERE readerId=:id") suspend fun imported(id:String):ReadingSyncImport?
    @Query("SELECT * FROM reading_sentences WHERE readerId=:id AND textChapter BETWEEN :first AND :last ORDER BY textChapter,textStart")
    suspend fun sentences(id:String,first:Int,last:Int):List<ReadingSentence>
    @Query("SELECT * FROM reading_sentences WHERE readerId=:id AND audioChapterId=:audio AND startMs<=:position AND endMs>=:position ORDER BY startMs DESC,textChapter,textStart LIMIT 1")
    suspend fun sentenceAtAudio(id:String,audio:Long,position:Long):ReadingSentence?
    @Query("SELECT * FROM reading_sentences WHERE readerId=:id AND audioChapterId=:audio AND endMs<:position ORDER BY endMs DESC LIMIT 1")
    suspend fun sentenceBeforeAudio(id:String,audio:Long,position:Long):ReadingSentence?
    @Query("SELECT * FROM reading_sentences WHERE readerId=:id AND audioChapterId=:audio AND startMs>:position ORDER BY startMs,textChapter,textStart LIMIT 1")
    suspend fun sentenceAfterAudio(id:String,audio:Long,position:Long):ReadingSentence?
    suspend fun sentenceNearAudio(id:String,audio:Long,position:Long):ReadingSentence? =
        sentenceAtAudio(id,audio,position) ?: SentenceAlignment.inSpeechGap(position,
            sentenceBeforeAudio(id,audio,position),sentenceAfterAudio(id,audio,position))
    @Query("DELETE FROM reading_sentences WHERE readerId=:id") suspend fun clearSentences(id:String)
    @Query("DELETE FROM reading_sync_imports WHERE readerId=:id") suspend fun clearImport(id:String)
    @Insert suspend fun insertPoints(points: List<ReadingSyncPoint>)
    @Query("DELETE FROM reading_sync WHERE readerId=:id") suspend fun clearPoints(id: String)
    @Query("DELETE FROM reading_sync WHERE readerId=:id AND textChapter=:chapter AND kind=:kind")
    suspend fun clearKind(id: String, chapter: Int, kind: String)
    @Query("DELETE FROM reading_sync WHERE readerId=:id AND kind='manual'") suspend fun clearManual(id: String)
}

data class TextChapter(val title: String, val length: Int)
data class ReadingLocation(val chapter: Int, val offset: Int)
data class AudioLocation(val chapterId: Long, val index: Int, val positionMs: Long, val approximate: Boolean)

/** Piecewise correspondence. Never extrapolate outside supported content or reverse time. */
object ReadingAlignment {
    private data class Node(val text: Long, val audio: Long, val point: ReadingSyncPoint)
    private fun prefix(values: List<Long>) = values.runningFold(0L) { a, b -> a + b.coerceAtLeast(0) }
    private fun nodes(text: List<TextChapter>, audio: List<Chapter>, points: List<ReadingSyncPoint>, approximate: Boolean): List<Node> {
        val t = prefix(text.map { it.length.toLong() })
        val a = prefix(audio.map { it.durationMs ?: 0 })
        return points.filter { approximate || it.kind != "chapter" }.mapNotNull { p ->
            val i = audio.indexOfFirst { it.id == p.audioChapterId }
            if (i < 0 || p.textChapter !in text.indices || p.textOffset !in 0..text[p.textChapter].length ||
                p.positionMs < 0 || audio[i].durationMs?.let { p.positionMs > it } != false) null
            else Node(t[p.textChapter] + p.textOffset, a[i] + p.positionMs, p)
        }.sortedWith(compareBy<Node> { it.text }.thenBy { if (it.point.kind == "chapter") 1 else 0 })
            .distinctBy { it.text }
    }
    private fun interpolate(value: Long, x1: Long, x2: Long, y1: Long, y2: Long): Long =
        y1 + ((value - x1).toDouble() / (x2 - x1).coerceAtLeast(1) * (y2 - y1)).roundToLong()
    private fun compatible(a: Node, b: Node, audio: List<Chapter>, allowApproximate: Boolean): Boolean = b.text > a.text && b.audio > a.audio &&
        audio.indexOfFirst { it.id==a.point.audioChapterId }.let { start ->
            val end=audio.indexOfFirst { it.id==b.point.audioChapterId }
            start>=0 && end>=start && (start..end).all { (audio[it].durationMs ?: 0)>0 }
        } &&
        (allowApproximate || !((a.point.kind in setOf("subtitle", "recognition") || b.point.kind in setOf("subtitle", "recognition")) &&
            (b.audio-a.audio > 90_000 || b.text-a.text > 2500)))

    fun calibrationIsOrdered(text: List<TextChapter>, audio: List<Chapter>, points: List<ReadingSyncPoint>,
        point: ReadingSyncPoint): Boolean {
        val retained=points.filterNot { it.textChapter==point.textChapter && it.textOffset==point.textOffset }
        val all=nodes(text,audio,retained+point,approximate=false)
        return all.zipWithNext().all { (a,b)->b.audio>a.audio }
    }

    fun toAudio(location: ReadingLocation, text: List<TextChapter>, audio: List<Chapter>,
        points: List<ReadingSyncPoint>, allowApproximate: Boolean = false): AudioLocation? {
        if (location.chapter !in text.indices) return null
        points.filter { (allowApproximate || it.kind != "chapter") && it.textChapter == location.chapter &&
            it.textOffset == location.offset }.sortedBy { if (it.kind == "chapter") 1 else 0 }.firstOrNull()?.let { point ->
            val index = audio.indexOfFirst { it.id == point.audioChapterId }
            if (index >= 0 && point.positionMs in 0..(audio[index].durationMs ?: Long.MAX_VALUE))
                return AudioLocation(point.audioChapterId,index,point.positionMs,point.kind == "chapter")
        }
        val x = text.take(location.chapter).sumOf { it.length.toLong() } + location.offset.coerceIn(0, text[location.chapter].length)
        val nodes = nodes(text, audio, points, allowApproximate)
        val exact = nodes.firstOrNull { it.text == x }
        val before = nodes.lastOrNull { it.text <= x } ?: return null
        val after = nodes.firstOrNull { it.text > x }
        val value = if (exact != null) exact.audio else {
            if (after == null || !compatible(before, after, audio, allowApproximate)) return null
            interpolate(x, before.text, after.text, before.audio, after.audio)
        }
        val timeline = prefix(audio.map { it.durationMs ?: 0 })
        val index = if (exact != null) audio.indexOfFirst { it.id == exact.point.audioChapterId }
            else (audio.indexOfFirst { it.id==before.point.audioChapterId }..audio.indexOfFirst { it.id==after!!.point.audioChapterId })
                .lastOrNull { timeline[it] <= value } ?: return null
        val approximate = exact?.point?.kind == "chapter" || exact == null
        return AudioLocation(audio[index].id, index, (value - timeline[index]).coerceIn(0, audio[index].durationMs ?: Long.MAX_VALUE), approximate)
    }

    fun toText(chapterId: Long, positionMs: Long, text: List<TextChapter>, audio: List<Chapter>,
        points: List<ReadingSyncPoint>, allowApproximate: Boolean = false): ReadingLocation? {
        val index = audio.indexOfFirst { it.id == chapterId }
        if (index < 0) return null
        points.filter { (allowApproximate || it.kind != "chapter") && it.audioChapterId == chapterId &&
            it.positionMs == positionMs }.sortedBy { if (it.kind == "chapter") 1 else 0 }.firstOrNull()?.let { point ->
            if (point.textChapter in text.indices && point.textOffset in 0..text[point.textChapter].length)
                return ReadingLocation(point.textChapter,point.textOffset)
        }
        if ((audio[index].durationMs ?: 0)<=0) return null
        val value = audio.take(index).sumOf { it.durationMs ?: 0 } + positionMs.coerceIn(0, audio[index].durationMs!!)
        // Unknown tracks elsewhere must not disable a calibrated, contiguous known interval.
        val start=(index-1 downTo 0).firstOrNull { (audio[it].durationMs ?: 0)<=0 }?.plus(1) ?: 0
        val end=(index+1 until audio.size).firstOrNull { (audio[it].durationMs ?: 0)<=0 }?.minus(1) ?: audio.lastIndex
        val nodes = nodes(text, audio, points.filter { p->audio.indexOfFirst { it.id==p.audioChapterId } in start..end }, allowApproximate)
        val exact = nodes.firstOrNull { it.audio == value }
        val before = nodes.lastOrNull { it.audio <= value } ?: return null
        val after = nodes.firstOrNull { it.audio > value }
        val x = exact?.text ?: run {
            if (after == null || !compatible(before, after, audio, allowApproximate)) return null
            interpolate(value, before.audio, after.audio, before.text, after.text)
        }
        val starts = prefix(text.map { it.length.toLong() })
        val chapter = exact?.point?.textChapter ?: text.indices.lastOrNull { starts[it] <= x } ?: return null
        return ReadingLocation(chapter, (x - starts[chapter]).toInt().coerceIn(0, text[chapter].length))
    }

    fun chapterPoints(readerId: String, textIndex: Int, text: TextChapter, audio: Chapter): List<ReadingSyncPoint> {
        val duration = audio.durationMs?.takeIf { it > 0 } ?: return emptyList()
        return listOf(ReadingSyncPoint(readerId = readerId, textChapter = textIndex, textOffset = 0,
            audioChapterId = audio.id, positionMs = 0, kind = "chapter"),
            ReadingSyncPoint(readerId = readerId, textChapter = textIndex, textOffset = text.length,
                audioChapterId = audio.id, positionMs = duration, kind = "chapter"))
    }

    fun wholeBookPoints(id: String, text: List<TextChapter>, audio: List<Chapter>): List<ReadingSyncPoint> {
        if(text.isEmpty() || audio.isEmpty() || audio.any { (it.durationMs ?: 0)<=0 }) return emptyList()
        val start=text.indexOfFirst { ReaderMatching.chapterNumber(it.title)==1 }.coerceAtLeast(0)
        return listOf(ReadingSyncPoint(readerId=id,textChapter=start,textOffset=0,audioChapterId=audio.first().id,positionMs=0,kind="chapter"),
            ReadingSyncPoint(readerId=id,textChapter=text.lastIndex,textOffset=text.last().length,
                audioChapterId=audio.last().id,positionMs=audio.last().durationMs!!,kind="chapter"))
    }

    fun textLocation(offset: Int, chapters: List<TextChapter>): ReadingLocation {
        var rest=offset.coerceAtLeast(0)
        chapters.forEachIndexed { index, chapter ->
            if(rest<chapter.length || index==chapters.lastIndex) return ReadingLocation(index,rest.coerceAtMost(chapter.length))
            rest-=chapter.length
        }
        return ReadingLocation(0,0)
    }


    fun chapterSuggestions(text: List<TextChapter>, audio: List<Chapter>): List<Pair<Int, Int>> {
        val pattern = Regex("第[零〇一二三四五六七八九十百千万两0-9]+[章集节回]|chapter\\s*\\d+|[\\p{P}\\s]")
        fun key(s: String) = s.lowercase().replace(pattern, "")
        val titles = audio.indices.groupBy { key(audio[it].title) }
        return text.mapIndexedNotNull { i, t ->
            val title = key(t.title)
            titles[title]?.takeIf { title.isNotBlank() }?.singleOrNull()?.let { i to it }
        }
    }
}
