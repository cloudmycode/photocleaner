package com.maxdevlab.cleaner.photo

import com.maxdevlab.cleaner.photo.data.*
import org.junit.Assert.*
import org.junit.Test

class LibrarySnapshotTest {
    private val photos = (1..3).map { Photo("photo:$it", "$it.jpg", 1000L - it, 100, 100, 100, 1) }
    private val deleted = setOf(PhotoIdentity(photos[0].uri, photos[0].signature))
    private val records = photos.take(2).associate {
        it.uri to ReviewRecord(it.signature, if (it == photos[0]) Decision.REMOVE else Decision.KEEP, false)
    }

    @Test fun staleProviderRowsCannotResetProgressOrReenterSorting() {
        val library = LibrarySnapshot(photos, records).excluding(deleted)
        val group = ReviewRules.groups(library.photos, library.records).single()
        assertEquals(photos.drop(1), group.photos)
        assertEquals(1, group.reviewed)
        assertEquals(listOf(photos[2].uri), ReviewSession.start(group, library.records).uris)
        assertEquals(library, library.excluding(deleted))
    }

    @Test fun deletingLastRemovalKeepsMonthCompletedAndRevisitable() {
        val library = LibrarySnapshot(photos.take(2), records).excluding(deleted)
        val group = ReviewRules.groups(library.photos, library.records).single()
        assertTrue(group.completed)
        assertEquals(listOf(photos[1]), group.photos)
        assertTrue(ReviewSession.start(group, library.records).revisiting)
        assertEquals(listOf(photos[1].uri), ReviewSession.start(group, library.records).uris)
    }

    @Test fun reusedUriWithDifferentSignatureIsNotHiddenOrUnreviewed() {
        val replacement = photos[0].copy(modified = 2)
        val updated = mapOf(replacement.uri to ReviewRecord(replacement.signature, Decision.KEEP, false))
        val library = LibrarySnapshot(listOf(replacement), updated)
        assertEquals(library, library.excluding(deleted))
    }

    @Test fun deletingEveryPhotoRemovesMonthAndItsBrowsingQueue() {
        val allDeleted = photos.map { PhotoIdentity(it.uri, it.signature) }.toSet()
        val library = LibrarySnapshot(photos, records).excluding(allDeleted)
        assertTrue(ReviewRules.groups(library.photos, library.records).isEmpty())
        assertTrue(ReviewSession(photos.map { it.uri }, 3, false).reconcile(library.photos).uris.isEmpty())
    }
}
