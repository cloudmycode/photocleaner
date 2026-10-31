package com.maxdevlab.cleaner.photo.data

import android.content.Context
import com.maxdevlab.cleaner.photo.platform.MediaStorePhotoSource
import com.maxdevlab.cleaner.photo.platform.PhotoAccessPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotoRepository(context: Context) {
    private val database = ReviewDatabase(context)
    private val permissions = PhotoAccessPolicy(context)
    private val source = MediaStorePhotoSource(context)
    fun access() = permissions.access()
    suspend fun load(): LibrarySnapshot = withContext(Dispatchers.IO) {
        database.snapshot(if (access() == Access.NONE) emptyList() else source.query())
    }
    suspend fun write(uri: String, record: ReviewRecord?) = withContext(Dispatchers.IO) { database.write(uri, record) }
    suspend fun writeAll(records: Map<String, ReviewRecord>) = withContext(Dispatchers.IO) { database.writeAll(records) }
    suspend fun applyDeleteReport(report: DeleteReport) = withContext(Dispatchers.IO) { database.applyDeleteReport(report) }
    suspend fun reset() = withContext(Dispatchers.IO) { database.clear() }
}
