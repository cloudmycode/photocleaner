package com.maxdevlab.cleaner.photo.data

/** A browsing pass is separate from durable keep/remove decisions. */
data class ReviewSession(val uris: List<String>, val total: Int, val revisiting: Boolean) {
    fun advance() = copy(uris = uris.drop(1))
    fun undo(uri: String) = copy(uris = listOf(uri) + uris.filterNot { it == uri })
    fun reconcile(photos: List<Photo>): ReviewSession {
        val accessible = photos.map { it.uri }.toSet()
        val remaining = uris.filter { it in accessible }
        return copy(uris = remaining, total = total - (uris.size - remaining.size))
    }
    companion object {
        fun start(group: MonthGroup, records: Map<String, ReviewRecord>): ReviewSession {
            val pending = group.photos.filter { ReviewRules.record(it, records)?.decision == null }
            val photos = pending.ifEmpty { group.photos }
            return ReviewSession(photos.map { it.uri }, photos.size, pending.isEmpty())
        }
    }
}
