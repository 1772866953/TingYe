package com.tingye.app.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import java.util.Locale

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val coverUri: String? = null,
    val chapterIndex: Int = 0,
    val positionMs: Long = 0,
    val speed: Float = 1f,
    val status: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val chapterId: Long? = null,
    val sourceTreeUri: String? = null,
    @ColumnInfo(defaultValue = "0") val manualOrder: Boolean = false,
    val speedOverride: Float? = null,
    val introOverride: Int? = null,
    val outroOverride: Int? = null
)

@Entity(tableName = "chapters", indices = [Index(value = ["uri"], unique = true), Index("bookId")], foreignKeys = [ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val title: String,
    val uri: String,
    val orderIndex: Int,
    val durationMs: Long? = null,
    val relativePath: String? = null,
    @ColumnInfo(defaultValue = "0") val positionMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val listened: Boolean = false
)

@Entity(tableName = "bookmarks", indices = [Index("bookId"), Index("chapterId")],
    foreignKeys = [ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Chapter::class, parentColumns = ["id"], childColumns = ["chapterId"], onDelete = ForeignKey.CASCADE)])
data class Bookmark(@PrimaryKey(autoGenerate = true) val id: Long = 0, val bookId: Long,
    val chapterId: Long, val positionMs: Long, val createdAt: Long = System.currentTimeMillis(),
    val note: String = "")

@Dao
interface LibraryDao {
    @Query("SELECT * FROM listening_days ORDER BY day DESC") fun listeningDays(): Flow<List<ListeningDay>>
    @Query("SELECT * FROM listening_days ORDER BY day") suspend fun allListeningDays(): List<ListeningDay>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertListeningDay(day: ListeningDay)
    @Query("UPDATE listening_days SET durationMs=durationMs+:duration WHERE day=:day") suspend fun incrementListeningDay(day: String, duration: Long)
    @Query("UPDATE listening_days SET durationMs=MAX(durationMs,:duration) WHERE day=:day") suspend fun mergeListeningDay(day: String, duration: Long)
    @Transaction suspend fun addListeningTime(day: String, duration: Long) {
        insertListeningDay(ListeningDay(day, 0)); incrementListeningDay(day, duration.coerceAtLeast(0))
    }
    @Transaction suspend fun restoreListeningTime(day: String, duration: Long) {
        insertListeningDay(ListeningDay(day, 0)); mergeListeningDay(day, duration.coerceAtLeast(0))
    }
    @Query("SELECT COUNT(*) FROM books b WHERE EXISTS(SELECT 1 FROM chapters c WHERE c.bookId=b.id) AND NOT EXISTS(SELECT 1 FROM chapters c WHERE c.bookId=b.id AND c.listened=0)")
    fun completedBookCount(): Flow<Int>
    @Query("UPDATE chapters SET listened=:listened WHERE id=:id") suspend fun setChapterListened(id: Long, listened: Boolean)
    @Query("UPDATE books SET status=CASE WHEN NOT EXISTS(SELECT 1 FROM chapters c WHERE c.bookId=books.id AND c.listened=0) THEN 2 WHEN status=2 THEN 1 ELSE status END WHERE id=(SELECT bookId FROM chapters WHERE id=:chapterId)")
    suspend fun refreshListenedStatus(chapterId: Long)
    @Transaction suspend fun markChapterListened(id: Long, listened: Boolean) {
        setChapterListened(id, listened)
        refreshListenedStatus(id)
    }
    @Query("SELECT COUNT(*) FROM chapters WHERE bookId=:id AND listened=0") suspend fun unheardCount(id: Long): Int
    @Query("SELECT * FROM books ORDER BY updatedAt DESC") fun books(): Flow<List<Book>>
    @Query("SELECT * FROM books ORDER BY updatedAt DESC LIMIT 1") suspend fun latest(): Book?
    @Query("SELECT * FROM books ORDER BY CASE WHEN status>0 THEN 0 ELSE 1 END, updatedAt DESC LIMIT 1") suspend fun latestListening(): Book?
    @Query("SELECT * FROM books ORDER BY id") suspend fun allBooks(): List<Book>
    @Query("SELECT bookId, COUNT(*) AS count FROM chapters GROUP BY bookId") fun chapterCounts(): Flow<List<BookChapterCount>>
    @Query("SELECT * FROM books WHERE id = :id") suspend fun book(id: Long): Book?
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY orderIndex ASC, id ASC") fun chaptersFlow(bookId: Long): Flow<List<Chapter>>
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY orderIndex ASC, id ASC") suspend fun chapters(bookId: Long): List<Chapter>
    @Query("SELECT * FROM chapters WHERE id = :id") suspend fun chapter(id: Long): Chapter?
    @Query("SELECT * FROM chapters ORDER BY bookId, orderIndex, id") suspend fun allChapters(): List<Chapter>
    @Query("SELECT * FROM chapters ORDER BY bookId, orderIndex, id") fun allChaptersFlow(): Flow<List<Chapter>>
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC, id DESC") fun bookmarksFlow(bookId: Long): Flow<List<Bookmark>>
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC, id DESC") suspend fun bookmarks(bookId: Long): List<Bookmark>
    @Query("SELECT * FROM bookmarks") suspend fun allBookmarks(): List<Bookmark>
    @Query("SELECT * FROM chapters WHERE uri = :uri LIMIT 1") suspend fun findUri(uri: String): Chapter?
    @Query("UPDATE chapters SET uri = :uri, durationMs=NULL WHERE id = :id") suspend fun relink(id: Long, uri: String)
    @Insert suspend fun addBook(book: Book): Long
    @Insert suspend fun addChapters(chapters: List<Chapter>)
    @Insert suspend fun addChapter(chapter: Chapter): Long
    @Transaction suspend fun createBook(book: Book, chapters: List<Chapter>): Long {
        val id = addBook(book)
        addChapters(chapters.map { it.copy(bookId = id) })
        return id
    }
    @Transaction suspend fun appendChapters(bookId: Long, chapters: List<Chapter>, sourceTreeUri: String?) {
        if (chapters.isNotEmpty()) { addChapters(chapters); reopenCompleted(bookId) }
        if (sourceTreeUri != null) saveSourceTree(bookId, sourceTreeUri)
    }
    @Insert suspend fun addBookmark(bookmark: Bookmark): Long
    @Update suspend fun updateBookmark(bookmark: Bookmark)
    @Query("DELETE FROM bookmarks WHERE id = :id") suspend fun deleteBookmark(id: Long)
    @Update suspend fun updateBook(book: Book)
    @Query("UPDATE books SET title=:title WHERE id=:id") suspend fun updateBookTitle(id: Long, title: String)
    @Query("UPDATE books SET coverUri=:uri WHERE id=:id") suspend fun updateBookCover(id: Long, uri: String)
    @Query("UPDATE books SET speedOverride=:override, speed=:effective WHERE id=:id")
    suspend fun updateSpeedOverride(id: Long, override: Float?, effective: Float)
    @Query("UPDATE books SET introOverride=:intro, outroOverride=:outro WHERE id=:id")
    suspend fun updateEdgeOverrides(id: Long, intro: Int?, outro: Int?)
    @Query("UPDATE chapters SET orderIndex=:orderIndex WHERE id=:id")
    suspend fun updateChapterOrder(id: Long, orderIndex: Int)
    @Query("UPDATE chapters SET durationMs=:duration WHERE id=:id") suspend fun saveDuration(id: Long, duration: Long)
    @Query("UPDATE books SET manualOrder=1 WHERE id=:bookId") suspend fun markManualOrder(bookId: Long)
    @Query("UPDATE books SET chapterIndex=(SELECT COUNT(*) FROM chapters c WHERE c.bookId=books.id AND c.orderIndex < (SELECT orderIndex FROM chapters WHERE id=books.chapterId)) WHERE id=:bookId AND chapterId IS NOT NULL")
    suspend fun refreshDisplayIndex(bookId: Long)
    @Query("UPDATE books SET sourceTreeUri=:uri WHERE id=:bookId") suspend fun saveSourceTree(bookId: Long, uri: String)
    @Query("UPDATE books SET status=1 WHERE id=:bookId AND status=2") suspend fun reopenCompleted(bookId: Long)
    @Transaction suspend fun swapChapters(first: Chapter, second: Chapter) {
        updateChapterOrder(first.id, second.orderIndex)
        updateChapterOrder(second.id, first.orderIndex)
    }
    @Query("DELETE FROM books WHERE id = :id") suspend fun deleteBook(id: Long)
    @Query("UPDATE books SET chapterId=:chapterId, chapterIndex=:index, positionMs=:position, status=:status, updatedAt=:time WHERE id=:id")
    suspend fun saveBookProgress(id: Long, chapterId: Long, index: Int, position: Long, status: Int, time: Long)
    @Query("UPDATE chapters SET positionMs=:position WHERE id=:chapterId")
    suspend fun saveChapterProgress(chapterId: Long, position: Long)
    @Transaction suspend fun saveProgress(id: Long, chapterId: Long, index: Int, position: Long,
        status: Int, time: Long = System.currentTimeMillis()) {
        saveChapterProgress(chapterId, position)
        if (status == 2) markChapterListened(chapterId, true)
        saveBookProgress(id, chapterId, index, position, if (status == 2 && unheardCount(id) > 0) 1 else status, time)
    }
    @Query("UPDATE books SET speed=:speed WHERE id=:id") suspend fun saveSpeed(id: Long, speed: Float)
}

@Entity(tableName = "listening_days")
data class ListeningDay(@PrimaryKey val day: String, val durationMs: Long)

@Database(entities = [Book::class, Chapter::class, Bookmark::class, ListeningDay::class], version = 4, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() { abstract fun dao(): LibraryDao }

val LIBRARY_MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chapters ADD COLUMN listened INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE chapters SET listened=1 WHERE bookId IN (SELECT id FROM books WHERE status=2)")
        db.execSQL("CREATE TABLE IF NOT EXISTS listening_days (day TEXT NOT NULL PRIMARY KEY, durationMs INTEGER NOT NULL)")
    }
}

val LIBRARY_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chapters ADD COLUMN positionMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE chapters SET positionMs=COALESCE((SELECT CASE WHEN status=2 THEN 0 ELSE MAX(0, positionMs) END FROM books WHERE books.chapterId=chapters.id),0)")
    }
}

val LIBRARY_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN chapterId INTEGER")
        db.execSQL("ALTER TABLE books ADD COLUMN sourceTreeUri TEXT")
        db.execSQL("ALTER TABLE books ADD COLUMN manualOrder INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE books ADD COLUMN speedOverride REAL")
        db.execSQL("ALTER TABLE books ADD COLUMN introOverride INTEGER")
        db.execSQL("ALTER TABLE books ADD COLUMN outroOverride INTEGER")
        db.execSQL("ALTER TABLE chapters ADD COLUMN durationMs INTEGER")
        db.execSQL("ALTER TABLE chapters ADD COLUMN relativePath TEXT")
        db.query("SELECT id, chapterIndex FROM books").use { books ->
            while (books.moveToNext()) {
                val bookId = books.getLong(0)
                val oldIndex = books.getInt(1).coerceAtLeast(0)
                db.query("SELECT id FROM chapters WHERE bookId=? ORDER BY orderIndex,id LIMIT 1 OFFSET ?",
                    arrayOf<Any>(bookId, oldIndex)).use { chapter ->
                    if (chapter.moveToFirst()) db.execSQL("UPDATE books SET chapterId=? WHERE id=?",
                        arrayOf<Any>(chapter.getLong(0), bookId))
                }
            }
        }
        db.execSQL("UPDATE books SET speedOverride=speed WHERE speed != 1.0")
        db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId INTEGER NOT NULL, chapterId INTEGER NOT NULL, positionMs INTEGER NOT NULL, createdAt INTEGER NOT NULL, note TEXT NOT NULL, FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(chapterId) REFERENCES chapters(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_bookId ON bookmarks(bookId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_chapterId ON bookmarks(chapterId)")
    }
}

data class ImportResult(val bookId: Long?, val added: Int, val skipped: Int, val error: String? = null)
data class BookChapterCount(val bookId: Long, val count: Int)
data class ImportCandidate(val uri: Uri, val name: String, val relativePath: String)
data class ScanResult(val files: List<ImportCandidate>, val failedDirectories: Int)
data class AppendPreview(val bookId: Long, val files: List<ImportCandidate>, val added: Int,
    val duplicate: Int, val invalid: Int, val failedDirectories: Int, val sourceTreeUri: String? = null)

data class BatchBookGroup(val title: String, val files: List<ImportCandidate>, val sourceTreeUri: String,
    val duplicate: Int, val details: DetectedBook = DetectedBook(title)) {
    val freshCount get() = files.size - duplicate
}
data class BatchImportPreview(val groups: List<BatchBookGroup>, val failedDirectories: Int)

class LibraryRepository(private val context: Context, val dao: LibraryDao) {
    val books = dao.books()
    suspend fun previewFiles(uris: List<Uri>, name: String): SingleImportPreview = withContext(Dispatchers.IO) {
        val files = uris.mapNotNull { uri ->
            val file = DocumentFile.fromSingleUri(context, uri)
            val display = file?.name ?: uri.lastPathSegment ?: "音频"
            if (isAudio(display, file?.type)) ImportCandidate(uri.normalizeScheme(), display, display) else null
        }.sortedWith { a,b -> naturalCompare(a.relativePath, b.relativePath) }
        SingleImportPreview(files, BookMetadata.detect(context, files, name, null))
    }
    suspend fun previewFolder(tree: Uri, name: String, onProgress: (Int) -> Unit, isCancelled: () -> Boolean): SingleImportPreview {
        val scan = scanFolderDetailed(tree, onProgress, isCancelled)
        val files = scan.files.sortedWith { a,b -> naturalCompare(a.relativePath, b.relativePath) }
        return SingleImportPreview(files, BookMetadata.detect(context, files, name, tree.toString()), tree.toString(), scan.failedDirectories)
    }
    suspend fun importFiles(uris: List<Uri>, name: String): ImportResult {
        val files = withContext(Dispatchers.IO) { uris.mapNotNull { uri ->
            val file = DocumentFile.fromSingleUri(context, uri)
            val display = file?.name ?: uri.lastPathSegment ?: "音频"
            if (isAudio(display, file?.type)) ImportCandidate(uri.normalizeScheme(), display, display) else null
        } }
        return importCandidates(files, name)
    }

    suspend fun importCandidates(files: List<ImportCandidate>, name: String,
        sourceTreeUri: String? = null, details: DetectedBook? = null): ImportResult = withContext(Dispatchers.IO) {
        val sorted = files.sortedWith { a, b -> naturalCompare(a.relativePath, b.relativePath) }
        if (sorted.isEmpty()) return@withContext ImportResult(null, 0, 0, "没有找到可识别的音频文件")
        val unique = sorted.distinctBy { it.uri.toString() }
        val fresh = unique.filter { dao.findUri(it.uri.toString()) == null }
        if (fresh.isEmpty()) return@withContext ImportResult(null, 0, sorted.size, "所选音频已经导入")
        val detected = details ?: BookMetadata.detect(context, fresh, name, sourceTreeUri)
        val title = if (details == null) detected.title else name.trim().ifBlank { detected.title }
        val cover = BookMetadata.persistCover(context, detected.coverUri)
        val id = dao.createBook(Book(title = title, coverUri = cover, sourceTreeUri = sourceTreeUri), fresh.mapIndexed { index, file -> Chapter(bookId = 0,
            title = file.name.substringBeforeLast('.'), uri = file.uri.toString(), orderIndex = index,
            relativePath = file.relativePath) })
        ImportResult(id, fresh.size, sorted.size - fresh.size)
    }

    suspend fun scanFolderDetailed(tree: Uri, onProgress: (Int) -> Unit, isCancelled: () -> Boolean): ScanResult = withContext(Dispatchers.IO) {
        // Keep the granted tree URI, but honor a child document ID when rescanning a batch book.
        val rootId = if (DocumentsContract.isDocumentUri(context, tree)) DocumentsContract.getDocumentId(tree)
            else DocumentsContract.getTreeDocumentId(tree)
        val stack = ArrayDeque<Pair<String, String>>()
        stack.add(rootId to "")
        val found = mutableListOf<ImportCandidate>()
        var failed = 0
        while (stack.isNotEmpty() && !isCancelled()) {
            currentCoroutineContext().ensureActive()
            val (folderId, parent) = stack.removeLast()
            try {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
                context.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
                    ?.use { cursor ->
                        while (cursor.moveToNext() && !isCancelled()) {
                            val id = cursor.getString(0); val name = cursor.getString(1) ?: continue
                            val mime = cursor.getString(2)
                            val path = if (parent.isEmpty()) name else "$parent/$name"
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) stack.add(id to path)
                            else if (isAudio(name, mime)) found += ImportCandidate(
                                DocumentsContract.buildDocumentUriUsingTree(tree, id), name, path)
                        }
                    } ?: run { failed++ }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
              catch (_: Exception) { failed++ }
            onProgress(found.size)
        }
        ScanResult(found, failed)
    }

    suspend fun previewBatch(tree: Uri, rootName: String, onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean): BatchImportPreview = withContext(Dispatchers.IO) {
        val scan = scanFolderDetailed(tree, onProgress, isCancelled)
        val root = DocumentFile.fromTreeUri(context, tree)
        val folders = root?.listFiles()?.filter { it.isDirectory }?.associateBy { it.name }
            ?: throw IllegalStateException("无法读取所选文件夹，请重新选择")
        val groups = scan.files.groupBy { if ('/' in it.relativePath) it.relativePath.substringBefore('/') else "" }
            .map { (folder, files) ->
                val uri = if (folder.isEmpty()) tree else folders[folder]?.uri
                    ?: throw IllegalStateException("文件夹“$folder”已发生变化，请重新扫描")
                val candidates = files.distinctBy { it.uri.toString() }.map {
                    if (folder.isEmpty()) it else it.copy(relativePath = it.relativePath.substringAfter('/'))
                }
                var duplicates = 0
                for (file in candidates) if (dao.findUri(file.uri.toString()) != null) duplicates++
                val details = BookMetadata.detect(context, candidates, folder.ifEmpty { rootName }, uri.toString())
                BatchBookGroup(details.title, candidates, uri.toString(), duplicates, details)
            }.sortedWith { a, b -> naturalCompare(a.title, b.title) }
        BatchImportPreview(groups, scan.failedDirectories)
    }

    suspend fun indexDurations(chapters: List<Chapter>) = withContext(Dispatchers.IO) {
        for (chapter in chapters.filter { it.durationMs == null }) {
            currentCoroutineContext().ensureActive()
            delay(35) // Yield between files; metadata must not compete with touch/animation work.
            val reader = MediaMetadataRetriever()
            val duration = try {
                reader.setDataSource(context, Uri.parse(chapter.uri))
                reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
            } catch (_: Exception) { null } finally { runCatching { reader.release() } }
            dao.saveDuration(chapter.id, duration ?: -1L)
        }
    }

    suspend fun previewAppend(bookId: Long, files: List<ImportCandidate>, failedDirectories: Int = 0,
        sourceTreeUri: String? = null): AppendPreview = withContext(Dispatchers.IO) {
        val unique = files.distinctBy { it.uri.toString() }
        var duplicate = files.size - unique.size
        var invalid = 0
        val fresh = mutableListOf<ImportCandidate>()
        for (file in unique) {
            if (!isAudio(file.name, DocumentFile.fromSingleUri(context, file.uri)?.type)) invalid++
            else if (dao.findUri(file.uri.toString()) != null) duplicate++
            else fresh += file
        }
        AppendPreview(bookId, fresh.sortedWith { a, b -> naturalCompare(a.relativePath, b.relativePath) },
            fresh.size, duplicate, invalid, failedDirectories, sourceTreeUri)
    }

    suspend fun append(preview: AppendPreview): ImportResult = withContext(Dispatchers.IO) {
        val book = dao.book(preview.bookId) ?: return@withContext ImportResult(null, 0, 0, "目标书籍已不存在")
        val existing = dao.chapters(book.id)
        val fresh = preview.files.filter { dao.findUri(it.uri.toString()) == null }
        val start = (existing.maxOfOrNull { it.orderIndex } ?: -1) + 1
        dao.appendChapters(book.id, fresh.mapIndexed { index, file -> Chapter(bookId = book.id,
                title = file.name.substringBeforeLast('.'), uri = file.uri.toString(),
                orderIndex = start + index, relativePath = file.relativePath) }, preview.sourceTreeUri)
        ImportResult(book.id, fresh.size, preview.duplicate + preview.files.size - fresh.size,
            if (fresh.isEmpty()) "没有新增章节" else null)
    }

    suspend fun scanFolder(tree: Uri, onProgress: (Int) -> Unit, isCancelled: () -> Boolean): List<Uri> = withContext(Dispatchers.IO) {
        scanFolderDetailed(tree, onProgress, isCancelled).files.map { it.uri }
    }

    fun canOpen(uri: String): Boolean = runCatching { context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false }.getOrDefault(false)
}

private val numeric = Regex("\\d+|\\D+")
fun naturalCompare(a: String, b: String): Int {
    val left = numeric.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
    val right = numeric.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
    for (i in 0 until minOf(left.size, right.size)) {
        val x = left[i]; val y = right[i]
        val result = if (x.first().isDigit() && y.first().isDigit()) {
            val nx = x.trimStart('0').ifEmpty { "0" }; val ny = y.trimStart('0').ifEmpty { "0" }
            nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
        } else x.compareTo(y)
        if (result != 0) return result
    }
    return left.size.compareTo(right.size)
}

fun isAudio(name: String, mime: String?): Boolean = mime?.startsWith("audio/") == true || name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "amr", "3gp")
