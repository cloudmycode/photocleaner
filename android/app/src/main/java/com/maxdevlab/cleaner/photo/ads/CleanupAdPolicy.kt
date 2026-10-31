package com.maxdevlab.cleaner.photo.ads

import com.maxdevlab.cleaner.photo.data.DeleteReport

/** Counts completed cleanups, not photos or ad impressions. Recovery never calls this policy. */
data class CleanupAdHistory(val count: Int = 0, val lastReportId: String? = null) {
    fun record(report: DeleteReport): CleanupAdHistory {
        if (report.id == lastReportId || report.interrupted || report.deleted.isEmpty() || report.canceled.isNotEmpty()) return this
        return CleanupAdHistory((count + 1).coerceAtMost(3), report.id)
    }
    fun offersAdAfter(previous: CleanupAdHistory) = this != previous && count >= 3
}

/** One presentation per report; leaving the foreground forfeits an unclaimed ad opportunity. */
class CleanupPresentation {
    private var id: String? = null
    private var offerAd = false
    private var sound = false
    fun stage(reportId: String, eligible: Boolean, playSound: Boolean) {
        id = reportId; offerAd = eligible; sound = playSound
    }
    fun backgrounded() { offerAd = false }
    fun claimAd(reportId: String): Boolean {
        if (id != reportId) return false
        return offerAd.also { offerAd = false }
    }
    fun takeSound(reportId: String): Boolean {
        if (id != reportId) return false
        return sound.also { sound = false }
    }
}
