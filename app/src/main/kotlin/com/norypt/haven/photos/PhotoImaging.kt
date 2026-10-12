package com.norypt.haven.photos

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

data class Probe(val width: Int, val height: Int, val mimeType: String)

interface PhotoImaging {
    /** Size and type of [bytes], or null when it is not a readable image or exceeds [ImageBounds]. */
    fun probe(bytes: ByteArray): Probe?
    /** A JPEG at most [AndroidPhotoImaging.THUMB_SIDE] on its longest side, or null. */
    fun thumbnail(bytes: ByteArray): ByteArray?
    fun decode(bytes: ByteArray, maxSide: Int): ImageBitmap?
}

/**
 * Decodes only from memory, never through a file. ImageDecoder applies EXIF orientation; sizes are
 * checked in the header callback, before any pixel memory is allocated.
 */
class AndroidPhotoImaging : PhotoImaging {
    private class TooLarge : RuntimeException()

    override fun probe(bytes: ByteArray): Probe? = try {
        var probe: Probe? = null
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
            if (!ImageBounds.check(info.size.width, info.size.height)) throw TooLarge()
            probe = Probe(info.size.width, info.size.height, info.mimeType)
            d.setTargetSize(1, 1) // only the header matters; keep the pixel work tiny
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }.recycle()
        probe
    } catch (t: Throwable) {
        null // TooLarge, an unsupported format, a damaged file or out of memory: not importable
    }

    override fun thumbnail(bytes: ByteArray): ByteArray? {
        val bmp = decodeBitmap(bytes, THUMB_SIDE) ?: return null
        return try {
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        } catch (t: Throwable) {
            null
        } finally {
            bmp.recycle()
        }
    }

    override fun decode(bytes: ByteArray, maxSide: Int): ImageBitmap? = decodeBitmap(bytes, maxSide)?.asImageBitmap()

    private fun decodeBitmap(bytes: ByteArray, maxSide: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
            if (!ImageBounds.check(info.size.width, info.size.height)) throw TooLarge()
            val (w, h) = ImageBounds.fit(info.size.width, info.size.height, maxSide)
            d.setTargetSize(w, h)
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (t: Throwable) {
        null
    }

    companion object { const val THUMB_SIDE = 512 }
}
