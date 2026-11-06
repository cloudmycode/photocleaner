package com.maxdevlab.cleaner.photo

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.maxdevlab.cleaner.photo.data.*
import com.maxdevlab.cleaner.photo.platform.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Only synthetic app-private files/databases on an isolated emulator. */
class SmartScanPerformanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun requireIsolation() {
        check(BuildConfig.ISOLATED_TEST_BUILD && context.packageName.endsWith(".devicetest"))
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
    }

    @Test fun bufferedDecodeMatchesPreviousFingerprintIncludingLargeHeaderAndFlatImage() = runBlocking {
        requireIsolation()
        val dir = File(context.cacheDir, "scan-compatibility").apply { mkdirs() }
        try {
            val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(800 * 600) { i ->
                val x = i % 800; val y = i / 800
                android.graphics.Color.rgb((x * 3 + y) % 256, (y * 7) % 256, (x + y * 5) % 256)
            }
            bitmap.setPixels(pixels, 0, 800, 0, 0, 800, 600)
            val jpg = File(dir, "pattern.jpg")
            jpg.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val png = File(dir, "pattern.png")
            png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.eraseColor(android.graphics.Color.WHITE)
            val flat = File(dir, "flat.png")
            flat.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val padded = File(dir, "large-header.jpg")
            val bytes = jpg.readBytes()
            padded.outputStream().use { out ->
                out.write(bytes, 0, 2)
                repeat(2) { out.write(byteArrayOf(0xff.toByte(), 0xe2.toByte(), 0xea.toByte(), 0x62)); out.write(ByteArray(60000)) }
                out.write(bytes, 2, bytes.size - 2)
            }
            val decoder = PhotoFingerprintDecoder(context)
            for (file in listOf(jpg, png, flat, padded)) {
                val photo = Photo(Uri.fromFile(file).toString(), file.name, 1000, file.length(), 800, 600, 1)
                assertEquals(file.name, legacyFingerprint(file), decoder.decode(photo))
            }
            val broken = File(dir, "broken.jpg").apply { writeText("not an image") }
            assertNull(decoder.decode(Photo(Uri.fromFile(broken).toString(), broken.name, 0, broken.length(), 800, 600, 1)))
        } finally { dir.deleteRecursively() }
    }

    @Test fun compare12477IndividualCommitsWithBatches() {
        requireIsolation()
        val baselineName = "scan-perf-baseline.db"
        val batchName = "scan-perf-batch.db"
        context.deleteDatabase(baselineName); context.deleteDatabase(batchName)
        val store = SqliteFingerprintStore(context, batchName)
        try {
            val entries = (0 until 12477).map { CachedFingerprint("content://synthetic/$it", "1:1000:0:800:600", PhotoFingerprint("hash-$it", it.toLong(), 1000)) }
            val baseline = context.openOrCreateDatabase(baselineName, 0, null)
            val serialMs: Long
            baseline.use { db ->
                db.execSQL("CREATE TABLE fingerprint (uri TEXT PRIMARY KEY, signature TEXT NOT NULL, duplicate TEXT NOT NULL, difference INTEGER NOT NULL, variance INTEGER NOT NULL)")
                val start = SystemClock.elapsedRealtime()
                entries.forEach { entry ->
                    db.insertOrThrow("fingerprint", null, ContentValues().apply {
                        put("uri", entry.uri); put("signature", entry.signature); put("duplicate", entry.fingerprint.duplicate)
                        put("difference", entry.fingerprint.difference); put("variance", entry.fingerprint.variance)
                    })
                }
                serialMs = SystemClock.elapsedRealtime() - start
            }
            store.read() // Exclude initial schema creation from both measurements.
            val start = SystemClock.elapsedRealtime()
            entries.chunked(64).forEach(store::write)
            val batchMs = SystemClock.elapsedRealtime() - start
            assertEquals(entries.associateBy { it.uri }, store.read())
            val report = "{\"rows\":12477,\"individualCommitMs\":$serialMs,\"batchCommitMs\":$batchMs,\"individualTransactions\":12477,\"batchTransactions\":195}"
            File(context.getExternalFilesDir(null), "scan-performance.json").writeText(report)
            android.util.Log.i("SmartScanPerformance", report)
        } finally {
            store.close()
            context.deleteDatabase(baselineName); context.deleteDatabase(batchName)
        }
    }

    /** Original decoder/math retained only as a compatibility oracle. */
    private fun legacyFingerprint(file: File): PhotoFingerprint {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
        val bitmap = file.inputStream().use { BitmapFactory.decodeStream(it, null,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) }!!
        val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        try {
            val pixels = IntArray(1024); small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
            val gray = pixels.map { ((it shr 16 and 255) * 299 + (it shr 8 and 255) * 587 + (it and 255) * 114) / 1000 }
            val mean = gray.average()
            val variance = (gray.sumOf { (it - mean) * (it - mean) } / gray.size).toInt()
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(if (variance < 80) file.readBytes() else ByteArray(gray.size) { (gray[it] and 0xF0).toByte() })
            var difference = 0L
            for (y in 0..7) for (x in 0..7) if (gray[y * 4 * 32 + x * 3] > gray[y * 4 * 32 + (x + 1) * 3]) difference = difference or (1L shl (y * 8 + x))
            return PhotoFingerprint(digest.digest().joinToString("") { "%02x".format(it) }, difference, variance)
        } finally { if (small !== bitmap) small.recycle(); bitmap.recycle() }
    }
}
