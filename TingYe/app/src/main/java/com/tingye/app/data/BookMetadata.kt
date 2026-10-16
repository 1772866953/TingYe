package com.tingye.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

data class DetectedBook(val title: String, val coverUri: String? = null)
data class SingleImportPreview(val files: List<ImportCandidate>, val details: DetectedBook,
    val sourceTreeUri: String? = null, val failedDirectories: Int = 0)

fun usefulBookTitle(value: String?): String? = value?.trim()?.takeIf {
    it.isNotEmpty() && it.length <= 200 && it.lowercase() !in setOf("unknown", "unknown album", "未知", "未知专辑") &&
        !it.contains("://")
}

object BookMetadata {
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private fun imageBytes(context: Context, uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                if (output.size() + n > MAX_IMAGE_BYTES) return@use null
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
    }.getOrNull()

    private fun normalizedCover(bytes: ByteArray): ByteArray? {
        if (bytes.size > MAX_IMAGE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1024) inSampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val scale = (512f / maxOf(decoded.width, decoded.height)).coerceAtMost(1f)
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true) else decoded
        return try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() } }
        finally { if (bitmap !== decoded) bitmap.recycle(); decoded.recycle() }
    }

    private fun store(context: Context, bytes: ByteArray, preview: Boolean): String? {
        val jpeg = normalizedCover(bytes) ?: return null
        val hash = MessageDigest.getInstance("SHA-256").digest(jpeg).joinToString("") { "%02x".format(it) }
        val folder = File(if (preview) context.cacheDir else context.filesDir,
            if (preview) "import-covers" else "book-covers").apply { mkdirs() }
        val target = File(folder, "$hash.jpg")
        if (!target.exists()) target.writeBytes(jpeg)
        return Uri.fromFile(target).toString()
    }

    fun persistCover(context: Context, previewUri: String?): String? = previewUri?.let {
        imageBytes(context, Uri.parse(it))?.let { bytes -> store(context, bytes, false) }
    }
    fun restoreCover(context: Context, bytes: ByteArray): String? = store(context, bytes, false)
    fun discard(context: Context, coverUri: String?) {
        val uri = coverUri?.let(Uri::parse) ?: return
        val file = uri.path?.let(::File) ?: return
        if (uri.scheme == "file" && file.parentFile?.canonicalFile == File(context.cacheDir, "import-covers").canonicalFile)
            file.delete()
    }
    fun ownedCoverBytes(context: Context, uri: String?): ByteArray? {
        val parsed = uri?.let(Uri::parse) ?: return null
        val file = parsed.path?.let(::File) ?: return null
        return if (parsed.scheme == "file" && file.parentFile?.canonicalFile == File(context.filesDir, "book-covers").canonicalFile)
            imageBytes(context, parsed) else null
    }

    suspend fun detect(context: Context, files: List<ImportCandidate>, fallback: String, tree: String?): DetectedBook = withContext(Dispatchers.IO) {
        val albums = mutableListOf<String>(); var title: String? = null; var embedded: ByteArray? = null
        for (file in files.take(3)) {
            currentCoroutineContext().ensureActive()
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(context, file.uri)
                usefulBookTitle(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))?.let(albums::add)
                if (files.size == 1) title = usefulBookTitle(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
                if (embedded == null) embedded = reader.embeddedPicture?.takeIf { it.size <= MAX_IMAGE_BYTES }
            } catch (_: Exception) { /* Files without tags still import normally. */ }
            finally { runCatching { reader.release() } }
        }
        val folderCover = tree?.let { source -> runCatching {
            val uri = Uri.parse(source)
            val id = if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri)
                else DocumentsContract.getTreeDocumentId(uri)
            val images = mutableListOf<Pair<String, Uri>>()
            context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(uri, id),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    if (name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp"))
                        images += name to DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0))
                }
            }
            images.sortedBy { (name, _) -> if (name.substringBeforeLast('.').lowercase() in setOf("cover", "folder", "封面", fallback.lowercase())) 0 else 1 }
                .firstNotNullOfOrNull { (_, image) -> imageBytes(context, image)?.let { store(context, it, true) } }
        }.getOrNull() }
        val album = albums.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        DetectedBook(album ?: title ?: usefulBookTitle(fallback) ?: files.firstOrNull()?.name?.substringBeforeLast('.') ?: "新书",
            folderCover ?: embedded?.let { store(context, it, true) })
    }
}
