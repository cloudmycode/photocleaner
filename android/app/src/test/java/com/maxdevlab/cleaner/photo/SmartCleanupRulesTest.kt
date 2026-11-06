package com.maxdevlab.cleaner.photo

import com.maxdevlab.cleaner.photo.data.*
import org.junit.Assert.*
import org.junit.Test

class SmartCleanupRulesTest {
    private fun photo(id: Int, time: Long = id * 1000L) = Photo("content://test/$id", "IMG_$id.jpg", time, 4000, 1200, 900, 1)
    private fun fingerprint(id: String, hash: Long = 0, variance: Int = 1000) = PhotoFingerprint(id, hash, variance)
    @Test fun screenshotsUseDirectoryOrPrefixNotAnyOccurrenceInName() {
        assertTrue(ScreenshotRules.matches("anything.png", "Pictures/Screenshots/"))
        assertTrue(ScreenshotRules.matches("Screenshot_20261101.png", ""))
        assertTrue(ScreenshotRules.matches("截屏2026.png", ""))
        assertFalse(ScreenshotRules.matches("my_screenshot_reference.jpg", "DCIM/Camera/"))
        assertFalse(ScreenshotRules.matches("IMG_1.jpg", "Pictures/ScreenshotsBackup/"))
    }
    @Test fun duplicateRequiresFingerprintAndAspectAndKeepsFavorite() {
        val ps = (1..4).map { photo(it) }.map { if (it.uri.endsWith("4")) it.copy(width = 900) else it }
        val fps = ps.associate { it.uri to fingerprint(if (it.uri.endsWith("3")) "other" else "same") }
        val result = SmartCleanupRules.analyze(ps, fps, mapOf(ps[1].uri to ReviewRecord(ps[1].signature, null, true)))
            .filter { it.kind == CleanupKind.DUPLICATE }.single()
        assertEquals(listOf(ps[0], ps[1]), result.photos)
        assertEquals(ps[1].uri, result.bestUri)
        assertEquals(listOf(ps[0]), result.extras)
    }
    @Test fun nearbyUnrelatedPhotosAndFlatImagesAreNotBursts() {
        val ps = (1..3).map { photo(it) }
        assertTrue(SmartCleanupRules.analyze(ps, mapOf(ps[0].uri to fingerprint("a", 0), ps[1].uri to fingerprint("b", -1), ps[2].uri to fingerprint("c", -1, 0)), emptyMap()).none { it.kind == CleanupKind.BURST })
    }
    @Test fun burstRequiresBoundedTimeAndDoesNotUseScreenshotsOrImportTime() {
        val ps = listOf(photo(1), photo(2), photo(3, 10000), photo(4, 11000).copy(name = "Screenshot_1.png"), photo(5, 12000).copy(hasCaptureTime = false))
        val result = SmartCleanupRules.analyze(ps, ps.associate { it.uri to fingerprint(it.uri) }, emptyMap()).filter { it.kind == CleanupKind.BURST }
        assertEquals(listOf(ps.take(2)), result.map { it.photos })
    }
    @Test fun longSequenceIsSplitAndBestUsesResolutionThenBytes() {
        val ps = (0..24).map { photo(it).copy(bytes = 4000L + it) }
        val result = SmartCleanupRules.analyze(ps, ps.associate { it.uri to fingerprint(it.uri) }, emptyMap()).filter { it.kind == CleanupKind.BURST }
        assertEquals(2, result.size)
        assertTrue(result.all { g -> g.photos.last().takenAt - g.photos.first().takenAt <= 20000 })
        result.forEach { assertEquals(it.photos.last().uri, it.bestUri) }
    }
    @Test fun deletionAndPermissionChangesReconcileByIdentityAndKeepProcessedGroup() {
        val ps = (1..3).map { photo(it) }
        val group = CleanupGroup("group", CleanupKind.DUPLICATE, ps, ps[0].uri)
        val result = group.reconcile(listOf(ps[1], ps[2].copy(modified = 2)))!!
        assertEquals(listOf(ps[1]), result.photos)
        assertEquals(ps[1].uri, result.bestUri)
        assertTrue(result.extras.isEmpty())
        assertNull(group.reconcile(emptyList()))
    }
    @Test fun screenshotSessionExcludesCameraPhotosButSharesDurableDecisions() {
        val normal = photo(1)
        val shot = photo(2).copy(folder = "Pictures/Screenshots/")
        val olderShot = photo(3, 0).copy(name = "Screenshot_older.png")
        val records = mapOf(shot.uri to ReviewRecord(shot.signature, Decision.KEEP, false))
        val state = PhotoState(photos = listOf(normal, shot, olderShot), records = records, reviewSource = "screenshots")
        assertEquals(listOf(shot, olderShot), state.currentGroup!!.photos)
        val session = ReviewSession.start(state.currentGroup!!, records)
        assertEquals(listOf(olderShot.uri), state.copy(session = session).remaining.map { it.uri })
        assertEquals(1, state.currentGroup!!.reviewed)
    }
    @Test fun directCleanupSelectionDoesNotRequireMonthlyRemovalDecision() {
        val ps = (1..3).map { photo(it) }
        val group = CleanupGroup("group", CleanupKind.DUPLICATE, ps.take(2), ps[0].uri)
        val state = PhotoState(photos = ps, cleanupGroups = listOf(group), screen = "duplicates")
        assertEquals(listOf(ps[1]), state.cleanupCandidates(setOf(ps[1].uri, ps[2].uri, "inaccessible")))
        assertTrue(ReviewRules.deleteCandidates(ps, emptyMap(), setOf(ps[1].uri)).isEmpty())
    }
}
