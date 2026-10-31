package com.maxdevlab.cleaner.photo.platform

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.maxdevlab.cleaner.photo.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Metadata only; never decodes pixels, geocodes coordinates, or reads device location. */
class PhotoDetailsSource(private val context: Context, private val platform: PhotoPlatform = PhotoPlatform.current()) {
    suspend fun read(photo: Photo): PhotoDetails = withContext(Dispatchers.IO) {
        val fallback = PhotoDetails(photo.name, bytes = photo.bytes,
            dimensions = if (photo.width > 0 && photo.height > 0) "${photo.width} × ${photo.height}" else null,
            modified = PhotoDetailFormat.date(photo.modified * 1000))
        if (!TestMediaScope(context).allows(photo.uri)) return@withContext fallback.copy(unavailable = true)
        val uri = Uri.parse(photo.uri)
        val needsPermission = PhotoAccessPolicy(context, platform).needsMediaLocationPermission()
        @Suppress("DEPRECATION")
        val folderColumn = if (platform.supportsVolumes) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        val columns = arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.DATE_ADDED, folderColumn) +
            if (platform.supportsVolumes) arrayOf(MediaStore.Images.Media.VOLUME_NAME) else emptyArray()
        var details = try {
            context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use fallback.copy(unavailable = true)
                fun text(column: String) = c.getColumnIndex(column).takeIf { it >= 0 }?.let { c.getString(it) }?.takeIf { it.isNotBlank() }
                fun number(column: String) = text(column)?.toLongOrNull() ?: 0L
                val rawFolder = text(folderColumn)
                val folder = if (platform.supportsVolumes) rawFolder?.let {
                    val volume = text(MediaStore.Images.Media.VOLUME_NAME)
                    val storage = if (volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) "Internal storage" else "Storage ${volume.orEmpty()}".trim()
                    "$storage / $it"
                } else rawFolder?.substringBeforeLast('/', "")?.takeIf { it.isNotBlank() }
                fallback.copy(name = text(MediaStore.Images.Media.DISPLAY_NAME) ?: photo.name,
                    folder = folder, bytes = number(MediaStore.Images.Media.SIZE).takeIf { it > 0 } ?: photo.bytes,
                    type = text(MediaStore.Images.Media.MIME_TYPE),
                    taken = PhotoDetailFormat.date(number(MediaStore.Images.Media.DATE_TAKEN)),
                    modified = PhotoDetailFormat.date(number(MediaStore.Images.Media.DATE_MODIFIED) * 1000),
                    added = PhotoDetailFormat.date(number(MediaStore.Images.Media.DATE_ADDED) * 1000))
            } ?: fallback.copy(unavailable = true)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { fallback.copy(unavailable = true) }
        if (details.unavailable) return@withContext details
        details = details.copy(locationStatus = if (needsPermission) PhotoLocationStatus.PERMISSION_REQUIRED else PhotoLocationStatus.UNAVAILABLE)
        try {
            val original = if (Build.VERSION.SDK_INT >= 29 && !needsPermission) MediaStore.setRequireOriginal(uri) else uri
            context.contentResolver.openInputStream(original)?.use { stream ->
                val exif = ExifInterface(stream)
                val coordinates = FloatArray(2)
                val gps = if (!needsPermission && exif.getLatLong(coordinates))
                    PhotoDetailFormat.coordinates(coordinates[0].toDouble(), coordinates[1].toDouble()) else null
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
                val camera = if (model.startsWith(make, ignoreCase = true)) model else "$make $model".trim()
                details = details.copy(camera = camera.takeIf { it.isNotBlank() }, coordinates = gps,
                    locationStatus = when { needsPermission -> PhotoLocationStatus.PERMISSION_REQUIRED; gps != null -> PhotoLocationStatus.AVAILABLE; else -> PhotoLocationStatus.NOT_RECORDED })
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Unsupported or inaccessible EXIF does not hide file details. */ }
        details
    }
}
