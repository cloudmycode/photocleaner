package com.maxdevlab.cleaner.photo

import com.maxdevlab.cleaner.photo.data.*
import org.junit.Assert.*
import org.junit.Test

class ReviewSessionTest {
    private val photos = (1..3).map { Photo("photo:$it", "$it.jpg", 1, 100, 100, 100, 1) }
    private fun records(decision: Decision) = photos.associate { it.uri to ReviewRecord(it.signature, decision, false) }
    @Test fun incompleteMonthStartsWithOnlyUnreviewedPhotos() {
        val session = ReviewSession.start(MonthGroup("2026-10", photos, 1), records(Decision.KEEP).filterKeys { it == photos[0].uri })
        assertEquals(photos.drop(1).map { it.uri }, session.uris)
        assertEquals(2, session.total); assertFalse(session.revisiting)
    }
    @Test fun completedMonthReopensEveryPhotoIncludingMarkedPhotos() {
        for (decision in Decision.entries) {
            val session = ReviewSession.start(MonthGroup("2026-10", photos, 3), records(decision))
            assertEquals(photos.map { it.uri }, session.uris); assertTrue(session.revisiting)
        }
    }
    @Test fun revisitAdvancesRegardlessOfOldDecisionAndUndoRestoresPosition() {
        val session = ReviewSession.start(MonthGroup("2026-10", photos, 3), records(Decision.KEEP))
        val advanced = session.advance()
        assertEquals(photos[1].uri, advanced.uris.first())
        assertEquals(session, advanced.undo(photos[0].uri))
        assertTrue(session.advance().advance().advance().uris.isEmpty())
    }
    @Test fun externalDeletionRemovesOnlyUnavailableQueueEntries() {
        val session = ReviewSession(photos.map { it.uri }, 5, true)
        val next = session.reconcile(photos.drop(1))
        assertEquals(photos[1].uri, next.uris.first()); assertEquals(4, next.total)
        assertEquals(next, next.reconcile(photos.drop(1)))
    }
    @Test fun staleDecisionMakesPhotoPendingAgain() {
        val changed = photos[0].copy(modified = 2)
        val session = ReviewSession.start(MonthGroup("2026-10", listOf(changed) + photos.drop(1), 2), records(Decision.REMOVE))
        assertEquals(listOf(changed.uri), session.uris); assertFalse(session.revisiting)
    }
}
