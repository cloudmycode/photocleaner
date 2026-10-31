package com.maxdevlab.cleaner.photo.data

object CleanupSoundRules {
    fun completed(report: DeleteReport) = !report.interrupted && report.deleted.isNotEmpty() &&
        report.items.all { it.status == DeleteStatus.DELETED }
}
