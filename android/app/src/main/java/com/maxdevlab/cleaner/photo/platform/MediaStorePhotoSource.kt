package com.maxdevlab.cleaner.photo.platform

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.maxdevlab.cleaner.photo.data.Photo
import com.maxdevlab.cleaner.photo.data.DeleteItem
import com.maxdevlab.cleaner.photo.data.photoSignature

/** Calls run on IO. One mapper serves old external URIs and newer per-volume URIs. */
class MediaStorePhotoSource(private val context: Context, private val platform: PhotoPlatform = PhotoPlatform.current()) {
    private val scope = TestMediaScope(context)
    private val columns get() = listOf(
        MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT
    ) + if (platform.supportsVolumes) listOf(MediaStore.Images.Media.VOLUME_NAME) else emptyList()

    fun query(uri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ids: List<String>? = null): List<Photo> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (ids != null) {
            if (ids.isEmpty()) return emptyList()
            conditions += "${MediaStore.Images.Media._ID} IN (${ids.joinToString { "?" }})"
            args += ids
        }
        if (platform.supportsVolumes) conditions += "${MediaStore.Images.Media.IS_PENDING}=0"
        if (platform.supportsTrash) conditions += "${MediaStore.Images.Media.IS_TRASHED}=0"
        if (scope.enabled) {
            val names = scope.names()
            if (names.isEmpty()) return emptyList()
            conditions += "${MediaStore.Images.Media.DISPLAY_NAME} IN (${names.joinToString { "?" }})"
            args += names
        }
        return buildList {
            context.contentResolver.query(uri, columns.toTypedArray(), conditions.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
                args.takeIf { it.isNotEmpty() }?.toTypedArray(), "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media._ID} DESC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val photo = map(cursor)
                    if (scope.allows(photo.uri)) add(photo)
                }
            }
        }.sortedByDescending { it.takenAt }
    }
    fun isCurrent(uri: String, signature: String): Boolean {
        if (!scope.allows(uri)) return false
        val projection = arrayOf(MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT)
        val conditions = buildList {
            if (platform.supportsVolumes) add("${MediaStore.Images.Media.IS_PENDING}=0")
            if (platform.supportsTrash) add("${MediaStore.Images.Media.IS_TRASHED}=0")
        }
        return context.contentResolver.query(Uri.parse(uri), projection, conditions.takeIf { it.isNotEmpty() }?.joinToString(" AND "), null, null)?.use { c ->
            if (c.count != 1 || !c.moveToFirst()) false else {
                val taken = c.getLong(2).takeIf { it > 0 } ?: c.getLong(3) * 1000
                photoSignature(c.getLong(0), c.getLong(1).coerceAtLeast(0), taken, c.getInt(4), c.getInt(5)) == signature
            }
        } ?: false
    }
    /** Bound provider calls and SQL arguments; preserve each volume and exact content signature. */
    fun currentUris(items: List<DeleteItem>): Set<String> = buildSet {
        items.filter { scope.allows(it.uri) }.groupBy {
            val uri = Uri.parse(it.uri)
            uri.buildUpon().path(uri.path!!.substringBeforeLast('/')).build()
        }.forEach { (collection, group) ->
            group.chunked(200).forEach { chunk ->
                val expected = chunk.associateBy { it.uri }
                query(collection, chunk.map { ContentUris.parseId(Uri.parse(it.uri)).toString() }).forEach { photo ->
                    if (expected[photo.uri]?.signature == photo.signature) add(photo.uri)
                }
            }
        }
    }
    private fun map(c: Cursor): Photo {
        fun number(name: String) = c.getLong(c.getColumnIndexOrThrow(name))
        fun text(name: String) = c.getString(c.getColumnIndexOrThrow(name))
        val collection = if (platform.supportsVolumes) MediaStore.Images.Media.getContentUri(text(MediaStore.Images.Media.VOLUME_NAME)) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = ContentUris.withAppendedId(collection, number(MediaStore.Images.Media._ID)).toString()
        return Photo(uri, text(MediaStore.Images.Media.DISPLAY_NAME) ?: "Photo",
            number(MediaStore.Images.Media.DATE_TAKEN).takeIf { it > 0 } ?: number(MediaStore.Images.Media.DATE_ADDED) * 1000,
            number(MediaStore.Images.Media.SIZE).coerceAtLeast(0), number(MediaStore.Images.Media.WIDTH).toInt(),
            number(MediaStore.Images.Media.HEIGHT).toInt(), number(MediaStore.Images.Media.DATE_MODIFIED))
    }
}
