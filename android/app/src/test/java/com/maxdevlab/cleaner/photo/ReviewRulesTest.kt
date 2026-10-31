package com.maxdevlab.cleaner.photo

import com.maxdevlab.cleaner.photo.data.*
import com.maxdevlab.cleaner.photo.reminders.ReminderDates
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class ReviewRulesTest {
    private fun photo(id: Int, date: String = "2026-10-15T12:00:00Z") = Photo("content://media/external_primary/images/media/$id", "$id.jpg", Instant.parse(date).toEpochMilli(), 1000, 100, 100, 1)
    private fun record(p: Photo, decision: Decision? = Decision.REMOVE) = ReviewRecord(p.signature, decision, false)
    @Test fun changedPhotoDoesNotInheritAnOldDeleteDecision() {
        val old = photo(1); val edited = old.copy(bytes = 2000, modified = 2)
        assertNull(ReviewRules.record(edited, mapOf(old.uri to record(old))))
        assertTrue(ReviewRules.deleteCandidates(listOf(edited), mapOf(old.uri to record(old)), setOf(old.uri)).isEmpty())
    }
    @Test fun deleteRequiresAccessibleSelectedAndExplicitlyMarkedPhoto() {
        val photos = (1..4).map { photo(it) }
        val hidden = photo(5)
        val records = photos.associate { it.uri to record(it, if (it == photos[1]) Decision.KEEP else Decision.REMOVE) } + (hidden.uri to record(hidden))
        assertEquals(listOf(photos[0]), ReviewRules.deleteCandidates(photos, records, setOf(photos[0].uri, photos[1].uri, hidden.uri)))
    }
    @Test fun deletionIsUniqueAndCappedAtTwoThousand() {
        val photos = (1..2100).map { photo(it) }; val records = photos.associate { it.uri to record(it) }
        val selected = photos.map { it.uri }.toSet()
        val result = ReviewRules.deleteCandidates(photos + photos, records, selected)
        assertEquals(2000, result.size); assertEquals(2000, result.map { it.uri }.toSet().size)
    }
    @Test fun favoriteAloneDoesNotCountAsReviewedOrMarkedForDeletion() {
        val p = photo(1); val records = mapOf(p.uri to ReviewRecord(p.signature, null, true))
        assertEquals(0, ReviewRules.groups(listOf(p), records).single().reviewed)
        assertTrue(ReviewRules.deleteCandidates(listOf(p), records, setOf(p.uri)).isEmpty())
    }
    @Test fun monthsSortAcrossYearsAndOnlyValidDecisionsCount() {
        val photos = listOf(photo(1, "2025-12-15T12:00:00Z"), photo(2, "2026-01-15T12:00:00Z"), photo(3, "2026-01-20T12:00:00Z"))
        val records = mapOf(photos[1].uri to record(photos[1], Decision.KEEP), photos[2].uri to ReviewRecord("stale", Decision.REMOVE, false))
        val groups = ReviewRules.groups(photos, records)
        assertEquals(listOf("2026-01", "2025-12"), groups.map { it.id })
        assertEquals(photos[2], groups.first().photos.first()); assertEquals(.5f, groups.first().fraction)
        assertFalse(groups.first().completed)
    }
    @Test fun monthUsesDeviceTimeZoneAtUtcBoundary() {
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals("2025-12", photo(1, "2026-01-01T00:30:00Z").month)
        } finally { TimeZone.setDefault(old) }
    }
    @Test fun reminderBeforeCutoffUsesThisMonth() {
        val now = ZonedDateTime.of(2026, 10, 1, 19, 0, 0, 0, ZoneId.of("Europe/London"))
        assertEquals(now.withHour(20), ReminderDates.next(now))
    }
    @Test fun reminderAtCutoffMovesToNextMonthAndCrossesYear() {
        val now = ZonedDateTime.parse("2026-12-01T20:00:00Z")
        assertEquals(ZonedDateTime.parse("2027-01-01T20:00:00Z"), ReminderDates.next(now))
    }
    @Test fun reminderRetainsLocalHourAcrossDst() {
        val now = ZonedDateTime.of(2026, 3, 20, 10, 0, 0, 0, ZoneId.of("Europe/London"))
        val next = ReminderDates.next(now)
        assertEquals(20, next.hour); assertEquals(1, next.dayOfMonth); assertEquals(3600, next.offset.totalSeconds)
    }
}
