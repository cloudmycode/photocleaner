package com.maxdevlab.cleaner.photo.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.maxdevlab.cleaner.photo.data.Photo
import com.maxdevlab.cleaner.photo.data.PhotoFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest

/** Keep the existing visual fingerprint format so old cached entries remain valid. */
internal class PhotoFingerprintDecoder(private val context: Context) {
    suspend fun decode(photo: Photo): PhotoFingerprint? {
        val uri = Uri.parse(photo.uri)
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val input = context.contentResolver.openInputStream(uri)?.buffered(HEADER_BUFFER_SIZE) ?: return null
            val bitmap = input.use { stream ->
                stream.mark(HEADER_BUFFER_SIZE)
                BitmapFactory.decodeStream(stream, null, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
                currentCoroutineContext().ensureActive()
                if (runCatching { stream.reset() }.isSuccess) BitmapFactory.decodeStream(stream, null, options)
                else context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            } ?: return null
            try {
                val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
                try {
                    val pixels = IntArray(1024); small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
                    val gray = IntArray(pixels.size) { index ->
                        val pixel = pixels[index]
                        ((pixel shr 16 and 255) * 299 + (pixel shr 8 and 255) * 587 + (pixel and 255) * 114) / 1000
                    }
                    val mean = gray.average()
                    val variance = (gray.sumOf { (it - mean) * (it - mean) } / gray.size).toInt()
                    // Uniform images require exact encoded bytes rather than a coarse visual fingerprint.
                    val digest = MessageDigest.getInstance("SHA-256")
                    if (variance < 80) context.contentResolver.openInputStream(uri)?.use { stream ->
                        val buffer = ByteArray(32768); var count = stream.read(buffer)
                        while (count >= 0) { currentCoroutineContext().ensureActive(); digest.update(buffer, 0, count); count = stream.read(buffer) }
                    } ?: return null
                    else digest.update(ByteArray(gray.size) { (gray[it] and 0xF0).toByte() })
                    var difference = 0L
                    for (y in 0..7) for (x in 0..7) if (gray[y * 4 * 32 + x * 3] > gray[y * 4 * 32 + (x + 1) * 3]) difference = difference or (1L shl (y * 8 + x))
                    PhotoFingerprint(digest.digest().toHex(), difference, variance)
                } finally { if (small !== bitmap) small.recycle() }
            } finally { bitmap.recycle() }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        return CharArray(size * 2) { index ->
            val value = this[index / 2].toInt() and 255
            digits[if (index % 2 == 0) value ushr 4 else value and 15]
        }.concatToString()
    }
    private companion object { const val HEADER_BUFFER_SIZE = 64 * 1024 }
}
