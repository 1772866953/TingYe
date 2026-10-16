package com.tingye.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import java.io.File
import java.security.MessageDigest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private object ImageCache {
    private val decodeMutex = Mutex()
    private val bitmaps = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun cached(key: String): Bitmap? = bitmaps.get(key)

    // Only persist sources with an observable revision. Unknown providers always decode
    // the original so replacing an image at the same URI cannot leave a stale disk entry.
    private fun diskEntry(context: Context, uri: Uri, maxDimension: Int): File? = runCatching {
        val revision = if (uri.scheme == "file") {
            val source = File(uri.path ?: return@runCatching null)
            if (!source.isFile) return@runCatching null
            "${source.lastModified()}:${source.length()}"
        } else context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (!cursor.moveToFirst() || modifiedColumn < 0 || sizeColumn < 0 ||
                cursor.isNull(modifiedColumn) || cursor.isNull(sizeColumn)) return@use null
            val modified = cursor.getLong(modifiedColumn)
            if (modified <= 0) null else "$modified:${cursor.getLong(sizeColumn)}"
        } ?: return@runCatching null
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("$maxDimension:$uri:$revision".toByteArray())
            .joinToString("") { "%02x".format(it) }
        File(File(context.cacheDir, "decoded-images-v1").apply { mkdirs() }, "$hash.png")
    }.getOrNull()

    private fun storeOnDisk(entry: File, bitmap: Bitmap) = runCatching {
        val temporary = File.createTempFile("decode-", ".tmp", entry.parentFile)
        try {
            temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!temporary.renameTo(entry)) return@runCatching
            val files = entry.parentFile?.listFiles()?.filter { it.extension == "png" }.orEmpty()
            var bytes = files.sumOf { it.length() }
            for (old in files.sortedBy { it.lastModified() }) {
                if (bytes <= 32L * 1024 * 1024) break
                val length = old.length()
                if (old.delete()) bytes -= length
            }
        } finally { temporary.delete() }
    }

    suspend fun load(context: Context, uri: String, maxDimension: Int): Bitmap? {
        val key = "$maxDimension:$uri"
        cached(key)?.let { return it }
        return decodeMutex.withLock {
            cached(key)?.let { return@withLock it }
            val decoded = withContext(Dispatchers.IO) {
                runCatching {
                    val parsed = Uri.parse(uri)
                    val disk = diskEntry(context, parsed, maxDimension)
                    disk?.takeIf { it.isFile }?.let { entry ->
                        BitmapFactory.decodeFile(entry.path)?.let { bitmap ->
                            entry.setLastModified(System.currentTimeMillis())
                            return@runCatching bitmap
                        }
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                    var sample = 1
                    while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, options) }
                        ?.also { bitmap -> if (disk != null) storeOnDisk(disk, bitmap) }
                }.getOrNull()
            }
            if (decoded != null) {
                // Schedule bitmap texture upload on the render thread before the first transition.
                withContext(Dispatchers.IO) { decoded.prepareToDraw() }
                bitmaps.put(key, decoded)
            }
            decoded
        }
    }
}

internal suspend fun preloadWallpaperImage(context: Context, uri: String) {
    ImageCache.load(context.applicationContext, uri, 1600)
}
internal suspend fun loadWidgetCover(context: Context, uri: String): Bitmap? = ImageCache.load(context.applicationContext, uri, 256)

@Composable internal fun rememberImage(uri: String?, maxDimension: Int): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val cacheKey = "$maxDimension:$uri"
    val initial = remember(cacheKey) { if (uri == null) null else ImageCache.cached(cacheKey) }
    // A retained cover changing URI must never show the previous book's bitmap.
    val bitmap by key(cacheKey) {
        produceState(initial, uri, maxDimension) {
            value = if (uri == null) null else ImageCache.load(context, uri, maxDimension)
        }
    }
    return remember(bitmap) { bitmap?.asImageBitmap() }
}
