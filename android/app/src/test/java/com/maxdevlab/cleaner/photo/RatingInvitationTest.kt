package com.maxdevlab.cleaner.photo

import com.maxdevlab.cleaner.photo.platform.RatingInvitation
import org.junit.Assert.*
import org.junit.Test

class RatingInvitationTest {
    @Test fun reminderReturnsExactlyThirtyDaysAfterRateTap() {
        val now = 1000L
        assertTrue(RatingInvitation().isVisible(now))
        val clicked = RatingInvitation().rateClicked(now)
        assertEquals(now + 30L * 24 * 60 * 60 * 1000, clicked.remindAfter)
        assertFalse(clicked.isVisible(now))
        assertFalse(clicked.isVisible(clicked.remindAfter - 1))
        assertTrue(clicked.isVisible(clicked.remindAfter))
    }
    @Test fun anotherRateTapRestartsTheCooldown() {
        val first = RatingInvitation().rateClicked(1000)
        val next = first.rateClicked(2000)
        assertEquals(first.remindAfter + 1000, next.remindAfter)
    }
}
