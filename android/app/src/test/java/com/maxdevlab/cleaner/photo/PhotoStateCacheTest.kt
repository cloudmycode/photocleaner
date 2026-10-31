package com.maxdevlab.cleaner.photo

import com.maxdevlab.cleaner.photo.data.*
import org.junit.Assert.*
import org.junit.Test

class PhotoStateCacheTest {
    private val photo = Photo("photo:1", "one.jpg", 1_760_000_000_000, 100, 100, 100, 1)
    private val initial = PhotoState(photos = listOf(photo), month = photo.month,
        session = ReviewSession(listOf(photo.uri), 1, false))
    @Test fun currentMonthFastPathMatchesTheFullLibraryGrouping() {
        val earlier = photo.copy(uri = "photo:2", takenAt = photo.takenAt - 60_000)
        val anotherMonth = photo.copy(uri = "photo:3", takenAt = photo.takenAt - 60L * 86400 * 1000)
        val state = initial.copy(photos = listOf(earlier, anotherMonth, photo),
            records = mapOf(photo.uri to ReviewRecord(photo.signature, Decision.KEEP, false)))
        assertEquals(state.groups.find { it.id == photo.month }, state.currentGroup)
        assertNull(state.copy(month = "1900-01").currentGroup)
    }
    @Test fun repeatedUiReadsReuseTheSameDerivedCollections() {
        assertSame(initial.groups, initial.groups)
        assertSame(initial.remaining, initial.remaining)
        assertSame(initial.marked, initial.marked)
    }
    @Test fun newDecisionSnapshotCannotReuseStaleProgressOrRemovalList() {
        assertFalse(initial.currentGroup!!.completed); assertTrue(initial.marked.isEmpty())
        val changed = initial.copy(records = mapOf(photo.uri to ReviewRecord(photo.signature, Decision.REMOVE, false)))
        assertTrue(changed.currentGroup!!.completed); assertEquals(listOf(photo), changed.marked)
        assertFalse(initial.currentGroup!!.completed); assertTrue(initial.marked.isEmpty())
    }
    @Test fun changedPhotoAndDeletionInvalidateCachedDecisionsAndQueue() {
        val sorted = initial.copy(records = mapOf(photo.uri to ReviewRecord(photo.signature, Decision.KEEP, false)))
        assertTrue(sorted.currentGroup!!.completed)
        val edited = sorted.copy(photos = listOf(photo.copy(modified = 2)))
        assertFalse(edited.currentGroup!!.completed)
        assertTrue(edited.copy(photos = emptyList()).remaining.isEmpty())
    }
}
