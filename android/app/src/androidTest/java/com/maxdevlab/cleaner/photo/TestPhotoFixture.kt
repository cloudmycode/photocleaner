package com.maxdevlab.cleaner.photo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.time.Instant
import java.util.UUID

/** Exact URI allowlist in a separately named app, never a full user-library fixture. */
class TestPhotoFixture(private val context: Context) {
    val uris = mutableListOf<Uri>()
    private val prefs = context.getSharedPreferences("isolated_test_media", Context.MODE_PRIVATE)
    fun cleanRegisteredPhotos() {
        val names = prefs.getStringSet("names", emptySet()).orEmpty()
        for (value in prefs.getStringSet("uris", emptySet()).orEmpty()) {
            val uri = Uri.parse(value)
            val owned = context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use {
                it.moveToFirst() && it.getString(0) in names && it.getString(0).startsWith("photocleaner-fixture-")
            } == true
            if (owned) context.contentResolver.delete(uri, null, null)
        }
        prefs.edit().clear().commit()
    }
    fun addVideo(): Uri {
        check(Build.VERSION.SDK_INT >= 29)
        val name = "photocleaner-fixture-${UUID.randomUUID()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PhotoCleanerTest")
            put(MediaStore.Video.Media.IS_PENDING, 1)
            put(MediaStore.Video.Media.DATE_TAKEN, Instant.parse("2026-09-25T12:00:00Z").toEpochMilli())
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        uris += uri
        prefs.edit().putStringSet("uris", uris.map { it.toString() }.toSet())
            .putStringSet("names", prefs.getStringSet("names", emptySet()).orEmpty() + name).commit()
        context.contentResolver.openOutputStream(uri)!!.use { out ->
            InstrumentationRegistry.getInstrumentation().context.assets.open("video-fixture.mp4").use { it.copyTo(out) }
        }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
    fun create(count: Int = 3, large: Boolean = false, storeListing: Boolean = false, smartCleanup: Boolean = false) {
        cleanRegisteredPhotos()
        val names = mutableSetOf<String>()
        val dates = if (smartCleanup) List(count) { index ->
            Instant.parse("2026-10-24T12:00:00Z").plusSeconds(when (index) { 0 -> 86400L; 1 -> 172800L; 2, 3, 4 -> index.toLong(); else -> index * 86400L }).toString()
        } else if (storeListing) List(count) { index ->
            "2026-${(10 - index / 8).toString().padStart(2, '0')}-${(24 - index % 8).toString().padStart(2, '0')}T12:00:00Z"
        } else if (count != 3) List(count) { Instant.parse("2026-09-01T12:00:00Z").plusSeconds(it.toLong() * 86400).toString() } else listOf("2026-09-20T12:00:00Z", "2026-09-15T12:00:00Z", "2026-08-15T12:00:00Z")
        dates.forEachIndexed { index, date ->
            val name = "photocleaner-fixture-${UUID.randomUUID()}.jpg"
            val timestamp = Instant.parse(date).toEpochMilli()
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_TAKEN, timestamp)
            }
            val legacyFile = if (Build.VERSION.SDK_INT < 29) File(context.getExternalFilesDir("Pictures"), name) else null
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, if (smartCleanup && index >= 6) "Pictures/Screenshots" else "Pictures/PhotoCleanerTest")
                values.put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                legacyFile!!.parentFile!!.mkdirs()
                @Suppress("DEPRECATION") values.put(MediaStore.Images.Media.DATA, legacyFile.absolutePath)
                values.put(MediaStore.Images.Media.WIDTH, 800); values.put(MediaStore.Images.Media.HEIGHT, 1000)
            }
            val asset = if (smartCleanup) when (index) { 0, 1 -> "lake.jpg"; 2, 3, 4 -> "mountain.jpg"; else -> "sea.jpg" }
                else if (storeListing) listOf("store/coast.png", "store/lake.png", "store/dunes.png")[index % 3]
                else listOf("lake.jpg", "mountain.jpg", "sea.jpg")[index % 3]
            val source = InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use {
                checkNotNull(BitmapFactory.decodeStream(it))
            }
            val bitmap = if (large) {
                val width = if (index % 2 == 0) 4032 else 3024
                val height = if (index % 2 == 0) 3024 else 4032
                Bitmap.createScaledBitmap(source, width, height, true).also { if (it !== source) source.recycle() }
            } else source
            if (legacyFile != null) {
                values.put(MediaStore.Images.Media.WIDTH, bitmap.width)
                values.put(MediaStore.Images.Media.HEIGHT, bitmap.height)
            }
            // Write complete EXIF before exposing the file to MediaStore's scanner.
            // Newer scanners need an explicit UTC offset to resolve capture dates reliably.
            val imageFile = legacyFile ?: File(context.cacheDir, name)
            imageFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            android.media.ExifInterface(imageFile.absolutePath).apply {
                if (index == 0 && InstrumentationRegistry.getArguments().getString("rotatedPhoto") == "true") {
                    setAttribute(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
                }
                setAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL, date.take(19).replace('-', ':').replace('T', ' '))
                setAttribute(android.media.ExifInterface.TAG_DATETIME, date.take(19).replace('-', ':').replace('T', ' '))
                if (Build.VERSION.SDK_INT >= 29) {
                    setAttribute(android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00")
                    setAttribute(android.media.ExifInterface.TAG_OFFSET_TIME, "+00:00")
                }
                if (index == 0) {
                    setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE, "37/1,46/1,2964/100")
                    setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                    setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE, "122/1,25/1,984/100")
                    setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
                }
                saveAttributes()
            }
            if (legacyFile != null) values.put(MediaStore.Images.Media.SIZE, imageFile.length())
            val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = checkNotNull(context.contentResolver.insert(collection, values))
            uris += uri; names += name
            // Register each fixture immediately, including partial setup, for safe cleanup.
            prefs.edit().putStringSet("uris", uris.map { it.toString() }.toSet()).putStringSet("names", names.toSet()).commit()
            if (legacyFile == null) {
                try {
                    context.contentResolver.openOutputStream(uri)!!.use { output -> imageFile.inputStream().use { it.copyTo(output) } }
                } finally { imageFile.delete() }
            }
            if (Build.VERSION.SDK_INT >= 29) context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.DATE_TAKEN, timestamp) }, null, null)
        }
    }
    fun count() = uris.count { uri -> context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { it.count > 0 } == true }
}
