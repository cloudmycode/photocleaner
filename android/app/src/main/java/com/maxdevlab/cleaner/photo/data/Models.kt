package com.maxdevlab.cleaner.photo.data

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun photoSignature(modified: Long, bytes: Long, takenAt: Long, width: Int, height: Int) = "$modified:$bytes:$takenAt:$width:$height"

enum class Access { NONE, SELECTED, FULL }
enum class Decision { KEEP, REMOVE }
data class Photo(val uri: String, val name: String, val takenAt: Long, val bytes: Long, val width: Int, val height: Int, val modified: Long, val folder: String = "", val hasCaptureTime: Boolean = true, val isVideo: Boolean = false, val durationMs: Long = 0) {
    val isScreenshot: Boolean = !isVideo && ScreenshotRules.matches(name, folder)
    val signature: String = photoSignature(modified, bytes, takenAt, width, height)
    val month: String = YearMonth.from(Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault())).toString()
    val dateLabel: String by lazy { DateLabel.format(Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault())) }
    private companion object { val DateLabel = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH) }
}
data class ReviewRecord(val signature: String, val decision: Decision?, val favorite: Boolean)
data class MonthGroup(val id: String, val photos: List<Photo>, val reviewed: Int) {
    val title: String get() = YearMonth.parse(id).format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH))
    val year: Int get() = YearMonth.parse(id).year
    val completed: Boolean get() = reviewed == photos.size
    val fraction: Float get() = if (photos.isEmpty()) 0f else reviewed.toFloat() / photos.size
}
object ReviewRules {
    const val DELETE_LIMIT = 2000
    fun record(photo: Photo, records: Map<String, ReviewRecord>): ReviewRecord? = records[photo.uri]?.takeIf { it.signature == photo.signature }
    fun groups(photos: List<Photo>, records: Map<String, ReviewRecord>): List<MonthGroup> = photos
        .groupBy { it.month }.toSortedMap(reverseOrder()).map { (id, items) -> group(id, items, records) }
    fun month(photos: List<Photo>, records: Map<String, ReviewRecord>, id: String?): MonthGroup? {
        if (id == null) return null
        return photos.filter { it.month == id }.takeIf { it.isNotEmpty() }?.let { group(id, it, records) }
    }
    private fun group(id: String, photos: List<Photo>, records: Map<String, ReviewRecord>) =
        MonthGroup(id, photos.sortedByDescending { it.takenAt }, photos.count { record(it, records)?.decision != null })
    fun deleteCandidates(photos: List<Photo>, records: Map<String, ReviewRecord>, selected: Set<String>): List<Photo> = photos.filter {
        it.uri in selected && record(it, records)?.decision == Decision.REMOVE
    }.distinctBy { it.uri }.take(DELETE_LIMIT)
}
fun formatBytes(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) String.format(Locale.ENGLISH, "%.2f GB", bytes / (1024.0 * 1024 * 1024)) else String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024))

fun photoCount(count: Int): String = "$count ${if (count == 1) "photo" else "photos"}"

fun mediaCount(photos: List<Photo>): String {
    val videos = photos.count { it.isVideo }
    return if (videos == 0) photoCount(photos.size) else "${photoCount(photos.size - videos)} · $videos ${if (videos == 1) "video" else "videos"}"
}
fun videoTime(ms: Long): String = "%d:%02d".format(java.util.Locale.ROOT, ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
enum class MediaFilter { ALL, PHOTOS, VIDEOS; fun includes(photo: Photo) = this == ALL || (this == VIDEOS) == photo.isVideo }
