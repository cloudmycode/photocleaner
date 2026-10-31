package com.maxdevlab.cleaner.photo.platform

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.maxdevlab.cleaner.photo.data.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** Shares the current original through a temporary, read-only URI grant; no image copy. */
object PhotoShare {
    suspend fun prepare(context: Context, photo: Photo): Intent = withContext(Dispatchers.IO) {
        val uri = Uri.parse(photo.uri)
        if (uri.scheme != "content" || uri.authority != "media" ||
            !MediaStorePhotoSource(context).isCurrent(photo.uri, photo.signature)) {
            throw FileNotFoundException("Photo changed or is no longer available")
        }
        val resolver = context.contentResolver
        (resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("Photo is unavailable")).close()
        val mimeType = resolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/*"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Photo", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, "Share photo").apply {
            clipData = send.clipData
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
