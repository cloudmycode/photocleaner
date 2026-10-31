package com.maxdevlab.cleaner.photo.ads

import com.maxdevlab.cleaner.photo.data.DeleteReport

/** Application-scoped memory only; Activity recreation and ad returns keep the same session. */
class CleanupAdSession {
    private var history = CleanupAdHistory()
    internal val count: Int get() = history.count

    fun record(report: DeleteReport): Boolean {
        val before = history
        history = before.record(report)
        return history.offersAdAfter(before)
    }
}
