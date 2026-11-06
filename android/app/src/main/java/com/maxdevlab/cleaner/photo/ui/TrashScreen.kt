package com.maxdevlab.cleaner.photo.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.maxdevlab.cleaner.photo.*
import com.maxdevlab.cleaner.photo.data.*

@Composable fun TrashScreen(state: PhotoState, selected: Set<String>, select: (Set<String>) -> Unit,
    model: PhotoViewModel, confirm: () -> Unit) {
    val photos = state.marked
    var videoUri by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("To remove", when (state.trashReturn) { "review" -> "Back to sorting"; "finish" -> "Back to summary"; else -> "Back to months" }, model::back)
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 5.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            DesignText(mediaCount(photos), size = 12, color = Muted)
            Spacer(Modifier.weight(1f))
            if (photos.isNotEmpty()) DesignTextButton(if (selected.size == photos.size) "Deselect all" else "Select all", enabled = !state.busy) {
                select(if (selected.size == photos.size) emptySet() else photos.map { it.uri }.toSet())
            }
        }
        if (photos.isEmpty()) DesignEmpty("Nothing to remove", "Photos you swipe down appear here.", Modifier.weight(1f).fillMaxWidth(),
            action = if (state.trashReturn == "review") null else { { DesignButton("Browse photos", Modifier.fillMaxWidth(), enabled = !state.busy, onClick = model::resumeReview) } })
        else LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            items(photos, key = { it.uri }) { photo ->
                val picked = photo.uri in selected
                Box(Modifier.aspectRatio(.8f).clip(RoundedCornerShape(9.dp))
                    .clickable(enabled = !state.busy, role = Role.Checkbox) { select(if (picked) selected - photo.uri else selected + photo.uri) }
                    .semantics { contentDescription = "Select ${photo.dateLabel}"; this.selected = picked }) {
                    PhotoImage(photo, Modifier.fillMaxSize())
                    if (photo.isVideo) VideoPlayButton(Modifier.align(Alignment.Center), enabled = !state.busy, diameter = 48.dp) { videoUri = photo.uri }
                    Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                        .background(if (picked) Accent else Color.White.copy(alpha = .25f)).border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        if (picked) Icon(DesignIcons.Check, null, Modifier.size(13.dp), tint = Color.White)
                    }
                }
            }
        }
        if (state.busy) DeletionProgressPanel(model)
        if (photos.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(start = 17.dp, end = 17.dp, top = 15.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DesignIconAction(DesignIcons.Undo, "Keep selected", Modifier.weight(1f), enabled = selected.isNotEmpty() && !state.busy, background = Soft, tint = Accent) { model.keepSelected(selected) }
            DesignIconAction(DesignIcons.Trash, if (selected.isEmpty()) "Delete" else "Delete · ${selected.size}", Modifier.weight(1f), enabled = selected.isNotEmpty() && !state.busy, background = Destructive, tint = Color.White, onClick = confirm)
        }
    }
    state.photos.firstOrNull { it.uri == videoUri }?.let { photo ->
        VideoPreview(photo, model.videoPositions, markedForRemoval = true) { choice ->
            videoUri = null
            if (choice == false) model.keepSelected(setOf(photo.uri))
        }
    }
}
