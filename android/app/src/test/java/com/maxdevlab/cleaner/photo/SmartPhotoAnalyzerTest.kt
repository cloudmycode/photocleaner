package com.maxdevlab.cleaner.photo

import com.maxdevlab.cleaner.photo.data.*
import com.maxdevlab.cleaner.photo.platform.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SmartPhotoAnalyzerTest {
    private fun photo(id: Int) = Photo("content://test/$id", "IMG_$id.jpg", id * 1000L, 1000, 1200, 900, 1)
    private fun fingerprint(photo: Photo) = PhotoFingerprint("pair-${photo.uri.substringAfterLast('/').toInt() / 2}", 0, 1000)
    private class MemoryStore : FingerprintStore {
        val entries = mutableMapOf<String, CachedFingerprint>()
        var transactions = 0
        override fun read() = entries.toMap()
        override fun write(entries: List<CachedFingerprint>) {
            if (entries.isNotEmpty()) transactions++
            entries.forEach { this.entries[it.uri] = it }
        }
    }

    @Test fun largeLibraryMatchesRulesAndWarmScanDoesNoDecodeOrWrites() = runBlocking {
        val photos = (0 until 12477).map(::photo)
        val store = MemoryStore()
        val decodes = AtomicInteger()
        val analyzer = SmartPhotoAnalyzer(store) { decodes.incrementAndGet(); fingerprint(it) }
        val expected = SmartCleanupRules.analyze(photos, photos.associate { it.uri to fingerprint(it) }, emptyMap())
        val counts = mutableListOf<Int>()
        val result = analyzer.analyze(photos, emptyMap()) { counts += it.scanned }
        assertEquals(expected, result.groups)
        assertEquals(12477, decodes.get())
        assertEquals(195, store.transactions)
        assertEquals(12477, counts.last())
        assertTrue(counts.zipWithNext().all { (a, b) -> b >= a })
        var first: SmartPhotoAnalyzer.Progress? = null
        val warm = analyzer.analyze(photos, emptyMap()) { if (first == null) first = it }
        assertEquals(result, warm)
        assertEquals(expected, first!!.groups)
        assertEquals(12477, first!!.scanned)
        assertEquals(12477, decodes.get())
        assertEquals(195, store.transactions)
    }

    @Test fun changesDecodeOnlyNewAndModifiedPhotosAndIgnoreVideosAndRevokedUris() = runBlocking {
        val store = MemoryStore()
        val decoded = java.util.Collections.synchronizedList(mutableListOf<String>())
        val analyzer = SmartPhotoAnalyzer(store) { decoded += it.uri; fingerprint(it) }
        val original = (0..4).map(::photo)
        analyzer.analyze(original, emptyMap()) {}
        decoded.clear()
        val updated = listOf(original[1], original[2].copy(modified = 2), photo(5), photo(6).copy(isVideo = true))
        val result = analyzer.analyze(updated, emptyMap()) {}
        assertEquals(setOf(original[2].uri, photo(5).uri), decoded.toSet())
        assertTrue(result.groups.flatMap { it.photos }.none { it.uri == original[0].uri || it.isVideo })
    }

    @Test fun twoWorkersAreBoundedAndCanceledScanResumesCommittedBatches() = runBlocking {
        val photos = (0..191).map(::photo)
        val store = MemoryStore()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val calls = AtomicInteger()
        val analyzer = SmartPhotoAnalyzer(store) {
            calls.incrementAndGet()
            val count = active.incrementAndGet(); peak.updateAndGet { old -> maxOf(count, old) }
            try { delay(2); fingerprint(it) } finally { active.decrementAndGet() }
        }
        val job = launch {
            analyzer.analyze(photos, emptyMap()) { if (it.scanned == 64) throw CancellationException("Stop after checkpoint") }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(64, store.entries.size)
        assertEquals(64, calls.get())
        analyzer.analyze(photos, emptyMap()) {}
        assertEquals(192, calls.get())
        assertEquals(2, peak.get())
        assertEquals(0, active.get())
    }

    @Test fun unreadablePhotosDoNotPreventProgressAndCanBeRetried() = runBlocking {
        val store = MemoryStore()
        var readable = false
        val analyzer = SmartPhotoAnalyzer(store) { if (it.uri == photo(0).uri && !readable) null else fingerprint(it) }
        var scanned = 0
        val failed = analyzer.analyze(listOf(photo(0), photo(1)), emptyMap()) { scanned = it.scanned }
        assertEquals(1, failed.skipped)
        assertEquals(2, scanned)
        readable = true
        assertEquals(0, analyzer.analyze(listOf(photo(0), photo(1)), emptyMap()) {}.skipped)
    }

    @Test fun partialGroupsArriveBeforeFinalBatchAndFinalGroupingIsUnchanged() = runBlocking {
        val photos = (0..191).map(::photo)
        val updates = mutableListOf<SmartPhotoAnalyzer.Progress>()
        val analyzer = SmartPhotoAnalyzer(MemoryStore()) { delay(15); fingerprint(it) }
        val result = analyzer.analyze(photos, emptyMap()) { updates += it }
        assertTrue(updates.any { it.scanned in 1 until photos.size && !it.groups.isNullOrEmpty() })
        assertEquals(SmartCleanupRules.analyze(photos, photos.associate { it.uri to fingerprint(it) }, emptyMap()), result.groups)
    }

    @Test fun burstCandidatesAreOnlyAnOrderingHint() {
        val photos = listOf(photo(0), photo(1), photo(10), photo(11).copy(isVideo = true), photo(12).copy(hasCaptureTime = false))
        assertEquals(setOf(photo(0).uri, photo(1).uri), SmartCleanupRules.burstCandidates(photos))
    }
}
