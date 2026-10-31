package com.maxdevlab.cleaner.photo.data

/** URI alone is insufficient: MediaStore may reuse an ID for a different photo. */
data class PhotoIdentity(val uri: String, val signature: String)

data class LibrarySnapshot(val photos: List<Photo>, val records: Map<String, ReviewRecord>) {
    fun excluding(deleted: Set<PhotoIdentity>): LibrarySnapshot {
        if (deleted.isEmpty()) return this
        return LibrarySnapshot(
            photos.filterNot { PhotoIdentity(it.uri, it.signature) in deleted },
            records.filterNot { (uri, record) -> PhotoIdentity(uri, record.signature) in deleted }
        )
    }
}
