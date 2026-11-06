package com.maxdevlab.cleaner.photo.ui

import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.maxdevlab.cleaner.photo.data.*
import kotlinx.coroutines.delay

/** One local player, owned by the preview; never plays behind a sorting card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun VideoPreview(photo: Photo, positions: MutableMap<String, Int>,
    markedForRemoval: Boolean, dismiss: (Boolean?) -> Unit) {
    // Null means untouched: merely previewing must never make a review decision.
    var removalChoice by rememberSaveable(photo.uri, photo.signature) { mutableStateOf<Boolean?>(null) }
    val closePreview = { dismiss(removalChoice) }
    val context = LocalContext.current
    val view = remember(photo.uri, photo.signature) { VideoView(context) }
    var player by remember(view) { mutableStateOf<MediaPlayer?>(null) }
    var ready by remember(view) { mutableStateOf(false) }
    var failed by remember(view) { mutableStateOf(false) }
    var playing by remember(view) { mutableStateOf(false) }
    var muted by remember(view) { mutableStateOf(false) }
    var duration by remember(view) { mutableIntStateOf(photo.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
    var position by remember(view) { mutableIntStateOf(positions[photo.uri] ?: 0) }
    var dragging by remember(view) { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(true) }
    fun pause() { if (ready) { position = view.currentPosition; positions[photo.uri] = position; view.pause() }; playing = false }
    fun load() { failed = false; ready = false; view.setVideoURI(Uri.parse(photo.uri)) }
    DisposableEffect(view) {
        view.setOnPreparedListener {
            player = it; ready = true; duration = it.duration.coerceAtLeast(0)
            view.seekTo(position.coerceIn(0, duration)); it.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
            if (foreground) { view.start(); playing = true }
        }
        view.setOnErrorListener { _, _, _ -> failed = true; ready = false; playing = false; true }
        view.setOnCompletionListener { playing = false; position = duration; positions[photo.uri] = 0 }
        load()
        onDispose {
            if (ready) positions[photo.uri] = if (view.currentPosition >= duration) 0 else view.currentPosition
            view.stopPlayback(); player = null
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { foreground = false; pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { foreground = true }
    LaunchedEffect(view, ready) {
        while (ready) { if (!dragging) position = view.currentPosition; delay(250) }
    }
    Dialog(onDismissRequest = closePreview, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            // The window manager's dim layer survives VideoView's native surface clearing.
            // Keep the dialog transparent so the single 80% black scrim is not compounded.
            window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setDimAmount(.80f)
            onDispose { }
        }
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
            // Draw controls after the native video surface, in their own layer (including on older devices).
            Row(Modifier.fillMaxWidth().zIndex(1f).graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            }, verticalAlignment = Alignment.CenterVertically) {
                DesignRound(Icons.Outlined.Close, "Close video", tint = Color.White, onClick = closePreview)
                DesignText("Video · ${formatBytes(photo.bytes)}", Modifier.weight(1f), color = Color.White,
                    align = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(enabled = ready, onClick = { muted = !muted; val volume = if (muted) 0f else 1f; player?.setVolume(volume, volume) }) { Icon(if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, if (muted) "Unmute" else "Mute", Modifier.size(26.dp), tint = Color.White.copy(alpha = if (ready) 1f else .4f)) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                if (!ready && !failed) CircularProgressIndicator()
                if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    DesignText("Video could not be played", color = Color.White)
                    IconButton(onClick = { load() }) { Icon(Icons.Filled.Refresh, "Retry playback", tint = Color.White) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(enabled = ready, onClick = { if (playing) pause() else { view.start(); playing = true } }) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause video" else "Play video", Modifier.size(32.dp), tint = Color.White.copy(alpha = if (ready) 1f else .4f)) }
                Slider(value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    onValueChange = { dragging = true; position = it.toInt() },
                    onValueChangeFinished = { if (ready) view.seekTo(position); dragging = false },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = ready,
                    thumb = {
                        Box(Modifier.size(if (dragging) 16.dp else 12.dp).background(
                            Color.White.copy(alpha = if (ready) 1f else .4f), CircleShape))
                    },
                    track = {
                        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                            val middle = size.height / 2
                            val fraction = (position.toFloat() / duration.coerceAtLeast(1)).coerceIn(0f, 1f)
                            drawLine(Color.White.copy(alpha = .25f), Offset(0f, middle), Offset(size.width, middle), size.height, StrokeCap.Round)
                            drawLine(Color.White.copy(alpha = if (ready) .92f else .4f), Offset(0f, middle), Offset(size.width * fraction, middle), size.height, StrokeCap.Round)
                        }
                    })
                Text("${videoTime(position.coerceIn(0, duration.coerceAtLeast(0)).toLong())}/${videoTime(duration.toLong())}",
                    color = Color.White.copy(alpha = .82f), fontSize = 12.sp,
                    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
                    letterSpacing = 0.sp, maxLines = 1, softWrap = false)
            }
            RemovalCheckbox(removalChoice ?: markedForRemoval,
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) { removalChoice = it }

        }
    }
}
