package com.tingye.app.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.io.ByteArrayOutputStream

data class BackupInspection(val payload: JSONObject, val books: Int, val chapters: Int,
    val bookmarks: Int, val conflicts: Int, val potentiallyNeedsAuthorization: Int,
    val settings: ListeningSettings)
data class BackupImportResult(val books: Int, val chapters: Int, val bookmarks: Int,
    val skippedBooks: Int, val skippedChapters: Int)

private fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)
private fun JSONObject.nullableLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else getLong(key)
private fun JSONObject.nullableInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else getInt(key)
private fun JSONObject.nullableFloat(key: String): Float? =
    if (!has(key) || isNull(key)) null else getDouble(key).toFloat()

object BackupCodec {
    private const val MAX_BYTES = 20 * 1024 * 1024
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    suspend fun export(context: Context, database: LibraryDatabase, settings: ListeningSettings,
        destination: Uri): Int = withContext(Dispatchers.IO) {
        val dao = database.dao()
        val books = dao.allBooks()
        val chapters = dao.allChapters()
        val bookmarks = dao.allBookmarks()
        val payload = JSONObject().apply {
            put("books", JSONArray().apply { books.forEach { book -> put(JSONObject().apply {
                put("id", book.id); put("title", book.title); put("coverUri", book.coverUri)
                BookMetadata.ownedCoverBytes(context, book.coverUri)?.let {
                    put("coverData", Base64.encodeToString(it, Base64.NO_WRAP))
                }
                put("chapterId", book.chapterId); put("chapterIndex", book.chapterIndex)
                put("positionMs", book.positionMs); put("speed", book.speed); put("status", book.status)
                put("updatedAt", book.updatedAt); put("sourceTreeUri", book.sourceTreeUri)
                put("manualOrder", book.manualOrder); put("speedOverride", book.speedOverride)
                put("introOverride", book.introOverride); put("outroOverride", book.outroOverride)
            }) } })
            put("chapters", JSONArray().apply { chapters.forEach { chapter -> put(JSONObject().apply {
                put("id", chapter.id); put("bookId", chapter.bookId); put("title", chapter.title)
                put("uri", chapter.uri); put("orderIndex", chapter.orderIndex)
                put("durationMs", chapter.durationMs); put("relativePath", chapter.relativePath)
                put("positionMs", chapter.positionMs)
                put("listened", chapter.listened)
            }) } })
            put("bookmarks", JSONArray().apply { bookmarks.forEach { bookmark -> put(JSONObject().apply {
                put("id", bookmark.id); put("bookId", bookmark.bookId); put("chapterId", bookmark.chapterId)
                put("positionMs", bookmark.positionMs); put("createdAt", bookmark.createdAt)
                put("note", bookmark.note)
            }) } })
            put("settings", JSONObject().apply {
                put("theme", settings.theme); put("rewind", settings.rewind)
                put("forward", settings.forward); put("resumeBack", settings.resumeBack)
                put("intro", settings.intro); put("outro", settings.outro)
                put("transitionSpeed", settings.transitionSpeed); put("wallpaper", settings.wallpaper)
                put("wallpaperUri", settings.wallpaperUri); put("wallpaperBlur", settings.wallpaperBlur)
                put("defaultSpeed", settings.defaultSpeed); put("hapticStrength", settings.hapticStrength)
                put("headphonePrevious", settings.headphonePrevious); put("headphoneNext", settings.headphoneNext)
                put("volumeLeveling", settings.volumeLeveling)
                put("progressUndoSeconds", settings.progressUndoSeconds)
            })
            put("listeningDays", JSONArray().apply { dao.allListeningDays().forEach { day ->
                put(JSONObject().put("day", day.day).put("durationMs", day.durationMs))
            } })
        }
        val body = payload.toString()
        val outer = JSONObject().put("format", "tingye-local-backup")
            .put("version", 1).put("sha256", digest(body)).put("payload", body).toString()
        require(outer.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份超过 20 MB 限制" }
        context.contentResolver.openOutputStream(destination, "wt")?.use {
            it.write(outer.toByteArray(Charsets.UTF_8)); it.flush()
        } ?: error("无法写入选择的位置")
        books.size
    }

    suspend fun inspect(context: Context, database: LibraryDatabase, source: Uri): BackupInspection = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(source)?.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                require(output.size() <= MAX_BYTES) { "备份文件过大" }
            }
            output.toByteArray()
        } ?: error("无法读取备份文件")
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        require(root.getString("format") == "tingye-local-backup") { "不是听页备份" }
        require(root.getInt("version") == 1) { "备份版本不兼容" }
        val body = root.getString("payload")
        require(digest(body).equals(root.getString("sha256"), ignoreCase = true)) { "备份校验失败，文件可能已损坏" }
        val payload = JSONObject(body)
        val books = payload.getJSONArray("books")
        val chapters = payload.getJSONArray("chapters")
        val bookmarks = payload.getJSONArray("bookmarks")
        require(books.length() <= 50_000 && chapters.length() <= 200_000 && bookmarks.length() <= 200_000) { "备份记录数量异常" }
        val bookIds = (0 until books.length()).map { books.getJSONObject(it).getLong("id") }.toSet()
        val chapterIds = (0 until chapters.length()).map { chapters.getJSONObject(it).getLong("id") }.toSet()
        require(bookIds.size == books.length() && chapterIds.size == chapters.length()) { "备份中有重复 ID" }
        for (i in 0 until chapters.length()) {
            val c = chapters.getJSONObject(i)
            require(c.getLong("bookId") in bookIds && c.getString("uri").isNotBlank()) { "备份章节关联无效" }
        }
        for (i in 0 until bookmarks.length()) {
            val mark = bookmarks.getJSONObject(i)
            require(mark.getLong("bookId") in bookIds && mark.getLong("chapterId") in chapterIds &&
                mark.getLong("positionMs") >= 0) { "备份书签关联无效" }
        }
        payload.optJSONArray("listeningDays")?.let { days ->
            require(days.length() <= 100_000) { "统计记录数量异常" }
            for (i in 0 until days.length()) {
                val day = days.getJSONObject(i)
                java.time.LocalDate.parse(day.getString("day"))
                require(day.getLong("durationMs") in 0..172_800_000L) { "统计时长异常" }
            }
        }
        val s = payload.getJSONObject("settings")
        val settings = ListeningSettings(s.optString("theme", "system"), s.optInt("rewind", 15),
            s.optInt("forward", 30), s.optInt("resumeBack", 3), s.optInt("intro", 0),
            s.optInt("outro", 0), s.optDouble("transitionSpeed", 1.0).toFloat(),
            s.optString("wallpaper", "sage"), s.nullableString("wallpaperUri"),
            s.optDouble("wallpaperBlur", 0.0).toFloat(), s.optDouble("defaultSpeed", 1.0).toFloat(),
            s.optInt("hapticStrength", 2).coerceIn(0, 3),
            validHeadphoneAction(s.nullableString("headphonePrevious")),
            validHeadphoneAction(s.nullableString("headphoneNext")), s.optBoolean("volumeLeveling", false),
            s.optInt("progressUndoSeconds", 12).coerceIn(1, 12))
        val existingNames = database.dao().allBooks().map { it.title.trim().lowercase() }.toSet()
        val conflicts = (0 until books.length()).count {
            books.getJSONObject(it).getString("title").trim().lowercase() in existingNames
        }
        BackupInspection(payload, books.length(), chapters.length(), bookmarks.length(), conflicts,
            chapters.length(), settings)
    }

    suspend fun import(context: Context, database: LibraryDatabase, inspection: BackupInspection): BackupImportResult = withContext(Dispatchers.IO) {
        database.withTransaction {
            val dao = database.dao()
            val payload = inspection.payload
            val books = payload.getJSONArray("books")
            val chapters = payload.getJSONArray("chapters")
            val bookmarks = payload.getJSONArray("bookmarks")
            val names = dao.allBooks().map { it.title.trim().lowercase() }.toMutableSet()
            val chapterMap = mutableMapOf<Long, Long>()
            var insertedBooks = 0
            var insertedChapters = 0
            var insertedBookmarks = 0
            var skippedBooks = 0
            var skippedChapters = 0
            for (i in 0 until books.length()) {
                val old = books.getJSONObject(i)
                val oldId = old.getLong("id")
                val title = old.getString("title").trim()
                if (title.isBlank() || title.lowercase() in names) { skippedBooks++; continue }
                val associated = (0 until chapters.length()).map { chapters.getJSONObject(it) }
                    .filter { it.getLong("bookId") == oldId }.sortedBy { it.getInt("orderIndex") }
                val fresh = associated.filter { dao.findUri(it.getString("uri")) == null }
                skippedChapters += associated.size - fresh.size
                if (fresh.isEmpty()) { skippedBooks++; continue }
                val cover = old.nullableString("coverData")?.let {
                    BookMetadata.restoreCover(context, Base64.decode(it, Base64.DEFAULT))
                } ?: old.nullableString("coverUri")
                val newId = dao.addBook(Book(title = title, coverUri = cover,
                    chapterIndex = old.optInt("chapterIndex", 0), positionMs = old.optLong("positionMs", 0).coerceAtLeast(0),
                    speed = old.optDouble("speed", 1.0).toFloat(), status = old.optInt("status", 0),
                    updatedAt = old.optLong("updatedAt", System.currentTimeMillis()),
                    sourceTreeUri = old.nullableString("sourceTreeUri"), manualOrder = old.optBoolean("manualOrder", false),
                    speedOverride = old.nullableFloat("speedOverride"),
                    introOverride = old.nullableInt("introOverride"), outroOverride = old.nullableInt("outroOverride")))
                insertedBooks++; names += title.lowercase()
                for (c in fresh) {
                    val newChapterId = dao.addChapter(Chapter(bookId = newId, title = c.getString("title"),
                        uri = c.getString("uri"), orderIndex = c.getInt("orderIndex"),
                        durationMs = c.nullableLong("durationMs"), relativePath = c.nullableString("relativePath"),
                        positionMs = c.optLong("positionMs", if (c.getLong("id") == old.nullableLong("chapterId") &&
                            old.optInt("status", 0) != 2) old.optLong("positionMs", 0) else 0).coerceAtLeast(0),
                        listened = c.optBoolean("listened", old.optInt("status", 0) == 2)))
                    chapterMap[c.getLong("id")] = newChapterId
                    insertedChapters++
                }
                val saved = old.nullableLong("chapterId")?.let { chapterMap[it] }
                val first = chapterMap[fresh.first().getLong("id")]
                dao.book(newId)?.let { dao.updateBook(it.copy(chapterId = saved ?: first,
                    positionMs = if (saved != null) it.positionMs else 0,
                    status = if (saved != null) it.status else 0)) }
            }
            for (i in 0 until bookmarks.length()) {
                val mark = bookmarks.getJSONObject(i)
                val newChapter = chapterMap[mark.getLong("chapterId")] ?: continue
                val chapter = dao.chapter(newChapter) ?: continue
                dao.addBookmark(Bookmark(bookId = chapter.bookId, chapterId = newChapter,
                    positionMs = mark.getLong("positionMs"), createdAt = mark.getLong("createdAt"),
                    note = mark.optString("note", "")))
                insertedBookmarks++
            }
            payload.optJSONArray("listeningDays")?.let { days ->
                for (i in 0 until days.length()) {
                    val day = days.getJSONObject(i)
                    dao.restoreListeningTime(day.getString("day"), day.getLong("durationMs"))
                }
            }
            BackupImportResult(insertedBooks, insertedChapters, insertedBookmarks,
                skippedBooks, skippedChapters)
        }
    }
}
