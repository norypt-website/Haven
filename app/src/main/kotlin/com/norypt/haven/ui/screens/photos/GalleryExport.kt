package com.norypt.haven.ui.screens.photos

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Restore to gallery": writes an original back through MediaStore into Pictures/Haven Restored.
 * An app's own inserts need no storage permission. The bytes go straight from memory into the
 * MediaStore stream; Haven never writes them to a file of its own.
 */
object GalleryExport {
    /** Where restored photos end up, as the user sees it in a file manager or gallery. */
    const val FOLDER = "Pictures/Haven Restored"

    fun fileName(mimeType: String, now: Long, index: Int): String {
        val ext = when (mimeType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            "image/gif" -> "gif"
            else -> "img"
        }
        return "haven-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + "-$index.$ext"
    }

    /** True once the photo is in the gallery. A half-written entry is removed again on failure. */
    suspend fun save(context: Context, bytes: ByteArray, mimeType: String, index: Int = 0): Boolean = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName(mimeType, System.currentTimeMillis(), index))
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Haven Restored")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) }.getOrNull()
            ?: return@withContext false
        try {
            val out = resolver.openOutputStream(uri, "w") ?: throw IllegalStateException("no output stream")
            out.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }
}
