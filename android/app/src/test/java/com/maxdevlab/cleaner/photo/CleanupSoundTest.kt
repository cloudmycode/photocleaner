package com.maxdevlab.cleaner.photo

import com.maxdevlab.cleaner.photo.data.*
import org.junit.Assert.*
import org.junit.Test

class CleanupSoundTest {
    @Test fun completionSoundRequiresSuccessfulUninterruptedCleanup() {
        val deleted = DeleteItem("photo:1", "sig", 100, DeleteStatus.DELETED)
        val report = DeleteReport("report", listOf(deleted), false)
        assertTrue(CleanupSoundRules.completed(report))
        assertFalse(CleanupSoundRules.completed(report.copy(interrupted = true)))
        assertFalse(CleanupSoundRules.completed(report.copy(items = emptyList())))
        for (status in DeleteStatus.entries.filterNot { it == DeleteStatus.DELETED }) {
            assertFalse(CleanupSoundRules.completed(report.copy(items = listOf(deleted, deleted.copy(uri = "photo:2", status = status)))))
        }
    }
}
