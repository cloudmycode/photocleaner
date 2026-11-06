package com.maxdevlab.cleaner.photo.data

import java.util.Locale

/** Classification is conservative: timestamps alone never make a burst. */
object ScreenshotRules {
    fun matches(name: String, folder: String): Boolean {
        val directories = folder.lowercase(Locale.ROOT).split('/', '\\')
        return directories.any { it in setOf("screenshots", "screenshot", "screen captures", "截屏", "截图") } ||
            Regex("^(screenshot|screen_shot|screen-shot|screen shot|截屏|截图)([ _.-]|\\d)", RegexOption.IGNORE_CASE).containsMatchIn(name)
    }
}
/** Retained by the ViewModel, never copied into Android's size-limited saved-instance bundle. */
data class CleanupSelection(val screen: String = "", val selected: Set<String> = emptySet(), val known: Set<String> = emptySet())
enum class CleanupKind(val title: String) { DUPLICATE("Duplicate photos"), BURST("Burst photos") }
data class PhotoFingerprint(val duplicate: String, val difference: Long, val variance: Int)
data class CleanupGroup(val id: String, val kind: CleanupKind, val photos: List<Photo>, val bestUri: String) {
    val extras get() = photos.filter { it.uri != bestUri }
    fun reconcile(available: List<Photo>): CleanupGroup? = reconcile(available.associateBy { PhotoIdentity(it.uri, it.signature) })
    fun reconcile(identities: Map<PhotoIdentity, Photo>): CleanupGroup? {
        val remaining = photos.mapNotNull { identities[PhotoIdentity(it.uri, it.signature)] }
        return if (remaining.isEmpty()) null else copy(photos = remaining,
            bestUri = bestUri.takeIf { id -> remaining.any { it.uri == id } } ?: remaining.first().uri)
    }
}
object SmartCleanupRules {
    /** Decode plausible burst neighbors first; final classification still requires visual similarity. */
    fun burstCandidates(photos: List<Photo>): Set<String> {
        val ordered = photos.filter { !it.isVideo && !it.isScreenshot && it.hasCaptureTime }
            .sortedWith(compareBy<Photo> { it.takenAt }.thenBy { it.uri })
        return buildSet {
            ordered.zipWithNext { first, second ->
                if (second.takenAt - first.takenAt in 0..2500 && first.width == second.width && first.height == second.height) {
                    add(first.uri); add(second.uri)
                }
            }
        }
    }

    fun analyze(photos: List<Photo>, fingerprints: Map<String, PhotoFingerprint>, records: Map<String, ReviewRecord>): List<CleanupGroup> {
        fun best(ps: List<Photo>, kind: CleanupKind): String = ps.firstOrNull { ReviewRules.record(it, records)?.favorite == true }?.uri
            ?: if (kind == CleanupKind.DUPLICATE) ps.minBy { it.takenAt }.uri
            else ps.maxWith(compareBy<Photo> { it.width.toLong() * it.height }.thenBy { it.bytes }.thenBy { -it.takenAt }).uri
        fun group(ps: List<Photo>, kind: CleanupKind): CleanupGroup {
            val ordered = ps.sortedWith(compareBy<Photo> { it.takenAt }.thenBy { it.uri })
            return CleanupGroup("${kind.name}:${ordered.first().uri}", kind, ordered, best(ordered, kind))
        }
        val duplicates = photos.filter { fingerprints[it.uri]?.duplicate != null }.groupBy {
            val aspect = if (it.height > 0) (it.width.toDouble() / it.height * 1000).toInt() else 0
            "$aspect:${fingerprints.getValue(it.uri).duplicate}"
        }.values.filter { it.size > 1 }.map { group(it, CleanupKind.DUPLICATE) }
        val bursts = mutableListOf<CleanupGroup>()
        var sequence = mutableListOf<Photo>()
        fun flush() { if (sequence.size > 1) bursts += group(sequence, CleanupKind.BURST); sequence = mutableListOf() }
        for (photo in photos.filter { !it.isScreenshot && it.hasCaptureTime }.sortedWith(compareBy<Photo> { it.takenAt }.thenBy { it.uri })) {
            val fp = fingerprints[photo.uri]
            val previous = sequence.lastOrNull()
            val first = sequence.firstOrNull()
            val similar = fp != null && fp.variance >= 80 && previous != null && first != null &&
                photo.takenAt - previous.takenAt in 0..2500 && photo.takenAt - first.takenAt <= 20000 &&
                photo.width == previous.width && photo.height == previous.height &&
                listOf(previous, first).all { other -> fingerprints[other.uri]?.let { old ->
                    old.variance >= 80 && java.lang.Long.bitCount(fp.difference xor old.difference) <= 12
                } == true }
            if (!similar) flush()
            if (fp != null) sequence.add(photo)
        }
        flush()
        return (duplicates + bursts).sortedByDescending { it.photos.first().takenAt }
    }
}
