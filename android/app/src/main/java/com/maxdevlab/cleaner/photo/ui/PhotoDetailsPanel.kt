package com.maxdevlab.cleaner.photo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maxdevlab.cleaner.photo.data.*
import com.maxdevlab.cleaner.photo.platform.PhotoDetailsSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@Composable internal fun PhotoDetailsPanel(photo: Photo, revision: Int, visible: Boolean,
    requestLocation: () -> Unit, modifier: Modifier = Modifier, interacting: () -> Boolean) {
    val context = LocalContext.current.applicationContext
    val latestInteraction by rememberUpdatedState(interacting)
    // Keep only this photo's metadata, including while closed; photo/permission changes reset it.
    key(photo.uri, photo.signature, revision) {
        val details by produceState<PhotoDetails?>(null) {
            do {
                snapshotFlow { !latestInteraction() }.first { it }
                delay(350)
            } while (latestInteraction())
            value = PhotoDetailsSource(context).read(photo) // File/EXIF work stays on IO.
        }
        AnimatedVisibility(visible, modifier, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
            val scroll = rememberLazyListState()
            // A fixed viewport avoids a height jump when data arrives; offscreen rows stay uncomposed.
            LazyColumn(Modifier.testTag("photo-details").height(240.dp).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(Color(0xB31C1C1E))
                .border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(16.dp))
                .pointerInput(Unit) { detectTapGestures { } }, state = scroll,
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item(key = "title") { DesignText("Photo details", size = 14, color = Paper, weight = FontWeight.Medium) }
                val info = details
                if (info == null) item(key = "loading") { DesignText("Loading details…", size = 12, color = Muted) }
                else {
                    if (info.unavailable) item { DesignText("This photo is no longer accessible.", size = 12, color = Paper) }
                    item(key = "file") { DetailRow("File", info.name) }
                    item(key = "folder") { DetailRow("Folder", info.folder ?: "Not provided by Android") }
                    item(key = "size") { DetailRow("Size", PhotoDetailFormat.size(info.bytes)) }
                    item(key = "dimensions") { DetailRow("Dimensions", info.dimensions ?: "Not provided") }
                    item(key = "type") { DetailRow("Type", info.type ?: "Not provided") }
                    item(key = "taken") { DetailRow("Taken", info.taken ?: "Not recorded") }
                    item(key = "modified") { DetailRow("Modified", info.modified ?: "Not provided") }
                    item(key = "added") { DetailRow("Added", info.added ?: "Not provided") }
                    info.camera?.let { item(key = "camera") { DetailRow("Camera", it) } }
                    item(key = "location") {
                        DetailRow("Photo location", when (info.locationStatus) {
                            PhotoLocationStatus.AVAILABLE -> info.coordinates.orEmpty()
                            PhotoLocationStatus.NOT_RECORDED -> "Not recorded in this photo"
                            PhotoLocationStatus.PERMISSION_REQUIRED -> "Photo location access required"
                            PhotoLocationStatus.UNAVAILABLE -> "Not available for this file"
                        })
                    }
                    if (info.locationStatus == PhotoLocationStatus.PERMISSION_REQUIRED) item(key = "location-access") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DesignText("Reads saved GPS information, not your current location.", size = 11, color = Muted)
                            DesignTextButton("Show photo location", onClick = requestLocation)
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        DesignText(label, size = 10, color = Color(0xFFB6B6BC))
        DesignText(value, size = 12, color = Paper)
    }
}
