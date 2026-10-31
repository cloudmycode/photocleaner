package com.maxdevlab.cleaner.photo

import com.maxdevlab.cleaner.photo.ads.*
import com.maxdevlab.cleaner.photo.data.*
import org.junit.Assert.*
import org.junit.Test

class CleanupAdPolicyTest {
    private fun report(id: String, vararg statuses: DeleteStatus, interrupted: Boolean = false) = DeleteReport(
        id, statuses.mapIndexed { i, status -> DeleteItem("photo:$i", "sig", 100, status) }, interrupted)

    @Test fun firstTwoCleanupsAreFreeThenEveryCleanupOffersOneAd() {
        var history = CleanupAdHistory()
        repeat(8) { index ->
            val next = history.record(report("cleanup:$index", DeleteStatus.DELETED))
            assertEquals(index >= 2, next.offersAdAfter(history))
            history = next
        }
    }
    @Test fun numberOfPhotosDoesNotChangeNumberOfCleanups() {
        val first = CleanupAdHistory().record(report("a", *Array(2000) { DeleteStatus.DELETED }))
        assertEquals(1, first.count)
        assertFalse(first.offersAdAfter(CleanupAdHistory()))
    }
    @Test fun canceledFailedEmptyAndInterruptedCleanupsDoNotAdvanceWarmup() {
        val history = CleanupAdHistory(2, "previous")
        for (report in listOf(report("empty"), report("failed", DeleteStatus.FAILED),
            report("canceled", DeleteStatus.CANCELED), report("partialCancel", DeleteStatus.DELETED, DeleteStatus.CANCELED),
            report("interrupted", DeleteStatus.DELETED, interrupted = true))) {
            assertEquals(history, history.record(report))
            assertFalse(history.record(report).offersAdAfter(history))
        }
    }
    @Test fun partialFailureWithConfirmedDeletionsCountsButSameReportNeverCountsTwice() {
        val before = CleanupAdHistory(2, "previous")
        val report = report("partial", DeleteStatus.DELETED, DeleteStatus.FAILED)
        val after = before.record(report)
        assertTrue(after.offersAdAfter(before))
        assertEquals(after, after.record(report))
        assertFalse(after.record(report).offersAdAfter(after))
    }
    @Test fun missingAnAdDoesNotCreateAdDebt() {
        val third = CleanupAdHistory(2, "second").record(report("third", DeleteStatus.DELETED))
        val fourth = third.record(report("fourth", DeleteStatus.DELETED))
        assertTrue(fourth.offersAdAfter(third))
        assertFalse(fourth.record(report("fourth", DeleteStatus.DELETED)).offersAdAfter(fourth))
    }
    @Test fun presentationCannotRepeatOrApplyToAnotherReport() {
        val pending = CleanupPresentation()
        pending.stage("a", eligible = true, playSound = true)
        assertFalse(pending.claimAd("old"))
        assertTrue(pending.claimAd("a"))
        assertFalse(pending.claimAd("a"))
        assertFalse(pending.takeSound("old"))
        assertTrue(pending.takeSound("a"))
        assertFalse(pending.takeSound("a"))
    }
    @Test fun backgroundForfeitsAdButPreservesResultSoundForVisibleResult() {
        val pending = CleanupPresentation()
        pending.stage("a", eligible = true, playSound = true)
        pending.backgrounded()
        assertFalse(pending.claimAd("a"))
        assertTrue(pending.takeSound("a"))
        pending.stage("b", eligible = false, playSound = false)
        assertFalse(pending.claimAd("b"))
        assertFalse(pending.takeSound("b"))
    }
}
