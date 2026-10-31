package com.maxdevlab.cleaner.photo.platform

import android.content.Context

/** A reminder cooldown after a Rate tap, not proof of a submitted store review. */
internal data class RatingInvitation(val remindAfter: Long = 0) {
    fun isVisible(now: Long) = now >= remindAfter
    fun rateClicked(now: Long) = copy(remindAfter = now + 30L * 24 * 60 * 60 * 1000)
}

internal class RatingInvitationStore(context: Context) {
    private val preferences = context.getSharedPreferences("rating_invitation", Context.MODE_PRIVATE)
    fun read() = RatingInvitation(preferences.getLong("rate_remind_after", 0))
    fun isVisible() = read().isVisible(System.currentTimeMillis())
    fun rateClicked() {
        preferences.edit().putLong("rate_remind_after", read().rateClicked(System.currentTimeMillis()).remindAfter).apply()
    }
}
