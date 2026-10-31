package com.maxdevlab.cleaner.photo.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxdevlab.cleaner.photo.PhotoViewModel
import com.maxdevlab.cleaner.photo.data.*

@Composable internal fun DeletionProgressPanel(model: PhotoViewModel) {
    val progress by model.deleteProgress.collectAsStateWithLifecycle()
    DeletionProgressPanel(progress)
}

@Composable internal fun DeletionProgressPanel(progress: DeleteProgress?) {
    val determinate = progress != null && progress.total > 0 &&
        progress.phase in listOf(DeletePhase.DELETING, DeletePhase.FINISHING)
    val fraction by animateFloatAsState(progress?.fraction ?: 0f, tween(180), label = "deletion-progress")
    Column(Modifier.fillMaxWidth().padding(horizontal = 17.dp).testTag("deletion-progress")
        .background(Accent.copy(alpha = .07f), RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DesignText(when (progress?.phase) {
                DeletePhase.PREPARING -> "Preparing cleanup…"
                DeletePhase.DELETING -> "Deleting photos…"
                DeletePhase.WAITING_FOR_SYSTEM -> "Waiting for Android…"
                DeletePhase.FINISHING -> "Updating library…"
                null -> "Saving changes…"
            }, size = 14, color = Accent)
            if (determinate) DesignText("${(progress!!.fraction * 100).toInt()}%", size = 14, color = Accent)
        }
        if (determinate) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp), color = Accent, trackColor = Soft)
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp), color = Accent, trackColor = Soft)
        if (progress != null && progress.total > 0) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DesignText("${progress.processed} of ${progress.total} processed", size = 12, color = Muted)
                DesignText("${PhotoDetailFormat.size(progress.freedBytes)} freed", size = 12, color = Muted)
            }
            if (progress.failed > 0) DesignText("${photoCount(progress.failed)} could not be deleted", size = 12, color = Muted)
        }
    }
}
