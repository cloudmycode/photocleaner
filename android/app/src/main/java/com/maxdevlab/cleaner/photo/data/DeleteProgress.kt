package com.maxdevlab.cleaner.photo.data

enum class DeletePhase { PREPARING, DELETING, WAITING_FOR_SYSTEM, FINISHING }

/** Counts durable outcomes, never an in-flight delete or an unapproved system request. */
data class DeleteProgress(
    val phase: DeletePhase = DeletePhase.PREPARING,
    val total: Int = 0,
    val processed: Int = 0,
    val deleted: Int = 0,
    val failed: Int = 0,
    val freedBytes: Long = 0
) {
    val fraction: Float get() = if (total == 0) 0f else processed.toFloat() / total

    companion object {
        fun from(session: DeleteSession, phase: DeletePhase): DeleteProgress {
            var processed = 0; var deleted = 0; var failed = 0; var bytes = 0L
            for (item in session.items) when (item.status) {
                DeleteStatus.DELETED -> { processed++; deleted++; bytes += item.bytes }
                DeleteStatus.FAILED -> { processed++; failed++ }
                DeleteStatus.CANCELED -> processed++
                else -> Unit
            }
            return DeleteProgress(phase, session.items.size, processed, deleted, failed, bytes)
        }
    }
}
