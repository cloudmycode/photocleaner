package com.maxdevlab.cleaner.photo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.UUID

/** Generated sample media for opt-in listing captures; never packaged in the release app. */
internal class StoreListingMedia(private val context: Context) {
    private val cleaner = TestPhotoFixture(context)
    private val prefs = context.getSharedPreferences("isolated_test_media", 0)
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

    fun clean() = cleaner.cleanRegisteredPhotos()

    fun create() {
        clean()
        val photos = listOf(
            Triple("coast", "2026-11-01T12:00:00Z", false),
            Triple("lake", "2026-11-01T11:00:00Z", false),
            Triple("dunes", "2026-11-01T10:00:00Z", false),
            Triple("dunes", "2026-10-24T12:00:00Z", false),
            Triple("dunes", "2026-10-24T12:00:01Z", false),
            Triple("dunes", "2026-10-24T12:00:02Z", false),
            Triple("coast", "2026-09-24T12:00:00Z", false),
            Triple("lake", "2026-08-24T12:00:00Z", false),
            Triple("coast", "2026-07-24T12:00:00Z", false),
            Triple("trip", "2026-10-15T12:00:00Z", true),
            Triple("recipe", "2026-10-14T12:00:00Z", true),
            Triple("notes", "2026-10-13T12:00:00Z", true),
            Triple("trip", "2026-09-15T12:00:00Z", true),
            Triple("recipe", "2026-08-15T12:00:00Z", true)
        )
        photos.forEach { (asset, date, screenshot) ->
            val file = File(context.cacheDir, "listing-${UUID.randomUUID()}.jpg")
            val bitmap = assets.open("store/$asset.png").use { BitmapFactory.decodeStream(it)!! }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
            val width = bitmap.width; val height = bitmap.height
            bitmap.recycle()
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date.take(19).replace('-', ':').replace('T', ' '))
                setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00")
                saveAttributes()
            }
            try { insert(file, date, false, screenshot, width, height) } finally { file.delete() }
        }
        val video = File(context.cacheDir, "listing-video.mp4")
        try {
            video.outputStream().use { out -> assets.open("store/coast-video.mp4").use { it.copyTo(out) } }
            insert(video, "2026-11-01T09:00:00Z", true, false, 720, 960)
        } finally { video.delete() }
    }

    private fun insert(file: File, date: String, video: Boolean, screenshot: Boolean, width: Int, height: Int) {
        val name = "photocleaner-fixture-${UUID.randomUUID()}.${if (video) "mp4" else "jpg"}"
        val time = Instant.parse(date).toEpochMilli()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, when { video -> "Movies/PhotoCleanerTest"; screenshot -> "Pictures/Screenshots"; else -> "Pictures/PhotoCleanerTest" })
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.WIDTH, width); put(MediaStore.MediaColumns.HEIGHT, height)
            put(MediaStore.Images.Media.DATE_TAKEN, time)
        }
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = checkNotNull(context.contentResolver.insert(collection, values))
        prefs.edit().putStringSet("uris", prefs.getStringSet("uris", emptySet()).orEmpty() + uri.toString())
            .putStringSet("names", prefs.getStringSet("names", emptySet()).orEmpty() + name).commit()
        context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0); put(MediaStore.Images.Media.DATE_TAKEN, time)
        }, null, null)
    }
}
