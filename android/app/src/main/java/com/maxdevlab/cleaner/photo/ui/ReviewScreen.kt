package com.maxdevlab.cleaner.photo.ui

import androidx.compose.animation.core.*
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import com.maxdevlab.cleaner.photo.ui.gestures.*
import com.maxdevlab.cleaner.photo.images.PreviewPolicy
import com.maxdevlab.cleaner.photo.*
import com.maxdevlab.cleaner.photo.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlin.math.abs

private val CssEase = CubicBezierEasing(.25f, .1f, .25f, 1f)
private class PhotoFlight(val photo: Photo) {
    var skip = false
    var animation: Job? = null
}
private val ButtonEase = CubicBezierEasing(.42f, 0f, .58f, 1f)

/** Only the two photo layers move. Controls and the black stage stay fixed. */
@Composable fun ReviewScreen(state: PhotoState, model: PhotoViewModel, metadataRevision: Int = 0,
    requestPhotoLocation: () -> Unit = {}, sharePhoto: suspend (Photo) -> Unit = {}) {
    val group = state.currentGroup
    val remaining = state.remaining
    val photo = remaining.firstOrNull()
    if (photo == null || group == null) return
    var y by remember(photo.uri) { mutableFloatStateOf(0f) }
    var x by remember(photo.uri) { mutableFloatStateOf(0f) }
    var rotation by remember(photo.uri) { mutableFloatStateOf(0f) }
    var nextScale by remember(photo.uri) { mutableFloatStateOf(.85f) }
    var opacity by remember(photo.uri) { mutableFloatStateOf(1f) }
    var stageSize by remember { mutableStateOf(IntSize.Zero) }
    val stageHeight = stageSize.height.coerceAtLeast(1)
    var displayedSize by remember(photo.uri, photo.signature) { mutableStateOf(IntSize(photo.width, photo.height)) }
    val frontBounds = remember(stageSize, displayedSize) {
        fittedPhotoBounds(Size(stageSize.width.toFloat(), stageSize.height.toFloat()), displayedSize.width, displayedSize.height)
    }
    val previewSize = remember(stageSize) { PreviewPolicy.size(stageSize.width, stageSize.height) }
    var animating by remember(photo.uri) { mutableStateOf(false) }
    val zoom = remember(photo.uri) { PhotoZoom() }
    var showVideo by rememberSaveable(photo.uri) { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    BackHandler(enabled = showDetails) { showDetails = false }
    var guide by remember(photo.uri) { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val inbox = remember(state.month, state.reviewSource) { SwipeInbox() }
    var flight by remember(state.month, state.reviewSource) { mutableStateOf<PhotoFlight?>(null) }
    var returning by remember { mutableStateOf<Job?>(null) }
    var pinchPhoto by remember { mutableStateOf<String?>(null) }
    val readyPhotos = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(remaining.take(2).map { it.uri }) {
        readyPhotos.keys.retainAll(remaining.take(2).map { it.uri }.toSet())
    }
    val density = LocalDensity.current.density
    val enabled = !showVideo && !state.busy && !sharing && !animating && flight == null && !zoom.active && inbox.stroke == null
    fun scaleFor(offset: Offset) = .85f + .15f * (abs(offset.y) / maxOf(120 * density, stageHeight * .42f)).coerceIn(0f, 1f)
    fun effectiveNextScale() = if (!animating) inbox.offsetFor(photo)?.let(::scaleFor) ?: nextScale else nextScale
    fun copyDrag(offset: Offset) {
        x = offset.x; y = offset.y; rotation = y / density / 80f
        nextScale = scaleFor(offset)
    }
    fun fastForward() {
        flight?.let { departing ->
            departing.skip = true
            departing.animation?.cancel()
            if (departing.photo.uri == photo.uri) { opacity = 0f; nextScale = 1f }
        }
    }
    suspend fun settle() {
        val startX = x; val startY = y; val startRotation = rotation; val startScale = nextScale
        animate(0f, 1f, animationSpec = tween(200, easing = CssEase)) { t, _ ->
            x = startX * (1 - t); y = startY * (1 - t); rotation = startRotation * (1 - t)
            nextScale = startScale + (.85f - startScale) * t; opacity = 1f
        }
        animating = false
    }
    val commit: (Decision, Boolean) -> Unit = { decision, gesture ->
        if (!state.busy && !sharing && !animating && flight == null && !zoom.active && readyPhotos[photo.uri] == photo.signature) {
            animating = true
            val departing = PhotoFlight(photo)
            flight = departing
            scope.launch {
                val direction = if (decision == Decision.KEEP) -1 else 1
                val startX = x; val startY = y; val startRotation = rotation; val startScale = nextScale
                val duration = if (gesture) 230 else 560
                val easing = if (gesture) CssEase else ButtonEase
                if (!departing.skip) {
                    val animation = launch {
                        animate(0f, 1f, animationSpec = tween(duration, easing = LinearEasing)) { time, _ ->
                            val t = easing.transform(time)
                            x = startX * (1 - t)
                            y = startY + (stageHeight * 1.1f * direction - startY) * t
                            rotation = startRotation + (5f * direction - startRotation) * t
                            nextScale = startScale + (1f - startScale) * t
                            val fade = if (gesture) time else ((time * duration - 420) / 140).coerceIn(0f, 1f)
                            opacity = 1 - CssEase.transform(fade)
                        }
                    }
                    departing.animation = animation
                    animation.join() // A fresh DOWN may cancel only the visual tail, never persistence.
                }
                opacity = 0f; nextScale = 1f
                model.decide(photo, decision)
                model.state.first { !it.busy }
                flight = null
                // A failed save invalidates the buffered next-photo stroke, never retargets it.
                if (model.state.value.remaining.firstOrNull()?.uri == photo.uri) {
                    inbox.clear(); opacity = 1f
                    returning = scope.launch { settle() }
                }
            }
        }
    }
    val cancel: () -> Unit = {
        val stroke = inbox.cancelActive()
        if (stroke != null && stroke.matches(photo) && flight == null && !zoom.active) {
            copyDrag(stroke.offset)
            returning?.cancel()
            animating = true
            returning = scope.launch { settle() }
        }
    }
    val queued = inbox.stroke?.takeIf { it.released }
    LaunchedEffect(queued, photo.uri, photo.signature, state.busy, flight) {
        if (queued != null && flight == null && !state.busy) {
            if (!queued.matches(photo)) { inbox.clear(); return@LaunchedEffect }
            // Let the newly promoted photo reach the display before starting its exit.
            withFrameNanos { }; withFrameNanos { }
            if (model.state.value.remaining.firstOrNull()?.let(queued::matches) == true) {
                inbox.take(photo)?.let { stroke ->
                    copyDrag(stroke.offset)
                    commit(if (stroke.offset.y < 0) Decision.KEEP else Decision.REMOVE, true)
                }
            } else inbox.clear()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        inbox.clear()
        fastForward()
        if (flight == null) {
            returning?.cancel(); animating = false
            x = 0f; y = 0f; rotation = 0f; nextScale = .85f; opacity = 1f
        }
    }
    // These callbacks update without restarting the pointer stream when a photo or busy flag changes.
    val downAction by rememberUpdatedState(newValue = {
        if (inbox.stroke != null) model.message("Saving your last swipe. Please wait.")
        else {
            val departing = flight
            // Persistence can finish between input events and the next Compose frame.
            val live = model.state.value
            val current = live.remaining.firstOrNull()
            val target = if (departing != null && departing.photo.uri == current?.uri) live.remaining.getOrNull(1) else current
            fastForward()
            if (target != null && !sharing && (!live.busy || departing != null)) {
                if (readyPhotos[target.uri] == target.signature) {
                    if (departing == null) { returning?.cancel(); animating = false }
                    inbox.begin(target, if (departing == null && target.uri == photo.uri) Offset(x, y) else Offset.Zero)
                } else model.message("Photo is still loading. Please wait.")
            }
        }
    })
    val dragAction by rememberUpdatedState(newValue = { amount: Offset -> inbox.drag(amount) })
    val endAction by rememberUpdatedState(newValue = {
        val stroke = inbox.stroke?.takeUnless { it.released }
        if (stroke != null) {
            val offset = stroke.offset
            if (abs(offset.y) > 80 * density && abs(offset.y) > abs(offset.x) * 1.2f) {
                inbox.release()
                if (stroke.matches(photo) && flight == null && !state.busy && !sharing && !animating && !zoom.active) {
                    inbox.take(photo)
                    copyDrag(offset)
                    commit(if (offset.y < 0) Decision.KEEP else Decision.REMOVE, true)
                }
            } else cancel()
        }
    })
    val cancelAction by rememberUpdatedState(cancel)
    val pinchStartAction by rememberUpdatedState(newValue = {
        val stroke = inbox.cancelActive()
        if (flight == null && !state.busy && !sharing && (stroke?.matches(photo) == true || zoom.active)) {
            returning?.cancel(); animating = false
            pinchPhoto = photo.uri
            zoom.begin(stroke?.offset ?: Offset(x, y))
            x = 0f; y = 0f; rotation = 0f; nextScale = .85f
        } else pinchPhoto = null
    })
    val pinchAction by rememberUpdatedState(newValue = { before: Offset, focus: Offset, multiplier: Float ->
        if (pinchPhoto == photo.uri) zoom.move(before, focus, multiplier)
    })
    val pinchEndAction by rememberUpdatedState(newValue = {
        if (pinchPhoto == photo.uri) zoom.release(scope)
        pinchPhoto = null
    })
    val zoomReturning by rememberUpdatedState(newValue = { zoom.active })
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().height(55.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            DesignRound(DesignIcons.Back, "Back to ${if (state.reviewSource == "screenshots") "screenshots" else "months"}", enabled = enabled, tint = DarkAccent, dimWhenDisabled = false) { model.navigate(state.reviewSource) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DesignText(state.reviewTitle, size = 15, color = Paper, weight = FontWeight(550), spacing = .1f)
                    if (state.reviewSource != "screenshots") DesignText(group.year.toString(), size = 15, color = Color(0xFF98989D), weight = FontWeight(550), spacing = .1f)
                }
                Spacer(Modifier.height(4.dp))
                DesignText("${(state.session?.total ?: remaining.size) - remaining.size + 1} / ${state.session?.total ?: remaining.size}", size = 10, color = Muted, spacing = 1f)
            }
            DesignRound(DesignIcons.Help, "Gesture guide", enabled = enabled, tint = DarkAccent, dimWhenDisabled = false) { guide = true }
        }
        val total = state.session?.total?.coerceAtLeast(1) ?: 1
        DesignProgress((total - remaining.size).toFloat() / total, Modifier.padding(horizontal = 18.dp).fillMaxWidth(), DarkAccent, Color(0x12FFFFFF))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 9.dp).clipToBounds().onSizeChanged { stageSize = it }) {
            if (previewSize != null) {
                remaining.getOrNull(2)?.let { PreloadPhoto(it, previewSize) }
                Box(Modifier.fillMaxSize().testTag("review-photo").pointerInput(state.month, state.reviewSource, stageSize, density) {
                    detectPhotoGestures({ zoomReturning() }, { dragAction(it) }, { endAction() }, { cancelAction() },
                        { pinchStartAction() }, { a, b, scale -> pinchAction(a, b, scale) }, { pinchEndAction() },
                        onDown = { downAction() }, onEnd = { cancelAction() },
                        onTap = { showDetails = false })
                }) {
                // A URI keeps its painter when promoted from the back to the front card.
                remaining.take(2).asReversed().forEach { item -> key(item.uri) {
                    val front = item.uri == photo.uri
                    val semantics = if (front) Modifier else Modifier.clearAndSetSemantics { }
                    Box(Modifier.fillMaxSize().then(semantics).drawWithCache {
                        val reveal = Path()
                        val radius = PhotoCornerRadius.toPx()
                        onDrawWithContent {
                            if (front) drawContent() else {
                                // Match the front photo's rounded edge until the next card is revealed.
                                val hidden = 1f - ((effectiveNextScale() - .85f) / .15f).coerceIn(0f, 1f)
                                val bounds = Rect(frontBounds.left * hidden, frontBounds.top * hidden,
                                    size.width - (size.width - frontBounds.right) * hidden,
                                    size.height - (size.height - frontBounds.bottom) * hidden)
                                reveal.rewind()
                                reveal.addRoundRect(RoundRect(bounds, CornerRadius(radius * hidden)))
                                clipPath(reveal) { this@onDrawWithContent.drawContent() }
                            }
                        }
                    }.graphicsLayer {
                        val drag = inbox.offsetFor(item)?.takeUnless { front && animating }
                        translationY = drag?.y ?: if (front) y else 0f
                        translationX = (drag?.x ?: if (front) x else 0f) * .12f
                        rotationZ = drag?.y?.div(density * 80f) ?: if (front) rotation else 0f
                        alpha = if (front) opacity else if (zoom.active) 0f else 1f
                        scaleX = if (front || drag != null) 1f else effectiveNextScale(); scaleY = scaleX
                    }) {
                        Box(Modifier.fillMaxSize().graphicsLayer {
                            if (front) {
                                val transform = zoom.transform
                                scaleX = transform.scale; scaleY = transform.scale
                                translationX = transform.offset.x; translationY = transform.offset.y
                            }
                        }) {
                            if (front) ReviewPhotoImage(item, previewSize, Modifier.fillMaxSize(),
                                interacting = { zoom.active || animating || inbox.stroke != null || x != 0f || y != 0f },
                                onImageSize = { displayedSize = it; readyPhotos[item.uri] = item.signature })
                            else PhotoImage(item, Modifier.fillMaxSize(), ContentScale.Fit, Color.Transparent, previewSize,
                                roundedFit = true, onReady = { readyPhotos[item.uri] = item.signature })
                        }
                        SwipeStamp(true, Modifier.align(Alignment.Center).graphicsLayer {
                            val drag = inbox.offsetFor(item)?.takeUnless { front && animating }
                            val delta = drag?.y ?: if (front) y else 0f
                            alpha = if (delta < 0) (abs(delta) / (100 * density)).coerceIn(0f, 1f) else 0f
                        })
                        SwipeStamp(false, Modifier.align(Alignment.Center).graphicsLayer {
                            val drag = inbox.offsetFor(item)?.takeUnless { front && animating }
                            val delta = drag?.y ?: if (front) y else 0f
                            alpha = if (delta > 0) (abs(delta) / (100 * density)).coerceIn(0f, 1f) else 0f
                        })
                    }
                } }
                }
            }
            if (photo.isVideo) VideoPlayButton(Modifier.align(Alignment.Center), enabled = enabled) { showVideo = true }
            DirectionButton("Keep", DesignIcons.Up, Modifier.align(Alignment.TopCenter).padding(top = 15.dp), enabled && readyPhotos[photo.uri] == photo.signature) { commit(Decision.KEEP, false) }
            DirectionButton("Remove", DesignIcons.Down, Modifier.align(Alignment.BottomCenter).padding(bottom = 15.dp), enabled && readyPhotos[photo.uri] == photo.signature) { commit(Decision.REMOVE, false) }
            PhotoDetailsPanel(photo, metadataRevision, showDetails, requestPhotoLocation,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 64.dp),
                interacting = { zoom.active || animating || inbox.stroke != null || x != 0f || y != 0f })
            DesignRound(DesignIcons.More, if (showDetails) "Hide photo details" else "Show photo details",
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 11.dp),
                enabled = enabled, tint = if (showDetails) DarkAccent else Paper, dimWhenDisabled = false) { showDetails = !showDetails }
            val fileSize = remember(photo.bytes) { if (photo.bytes > 0) PhotoDetailFormat.size(photo.bytes) else "—" }
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 5.dp, bottom = 11.dp)
                .height(44.dp).widthIn(min = 44.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                DesignText(fileSize, Modifier.testTag("photo-file-size"), size = 11, color = Paper.copy(alpha = .8f))
            }
        }
        Box(Modifier.fillMaxWidth().height(70.dp).padding(horizontal = 22.dp).testTag("review-toolbar")) {
            DesignRound(DesignIcons.Undo, "Undo last swipe", modifier = Modifier.align(Alignment.CenterStart), enabled = state.canUndo && enabled, tint = Paper,
                background = Color.White.copy(alpha = .04f), dimWhenDisabled = false, onClick = model::undo)
            DesignRound(DesignIcons.Share, "Share photo", modifier = Modifier.align(Alignment.Center),
                enabled = enabled && readyPhotos[photo.uri] == photo.signature, tint = Paper,
                background = Color.White.copy(alpha = .04f), dimWhenDisabled = false) {
                if (!sharing) {
                    sharing = true
                    showDetails = false
                    scope.launch { try { sharePhoto(photo) } finally { sharing = false } }
                }
            }
            Row(Modifier.align(Alignment.CenterEnd).height(44.dp).clip(CircleShape).background(Color.White.copy(alpha = .04f))
                .clickable(enabled = enabled, role = Role.Button) { model.navigate("trash") }.padding(horizontal = 15.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(DesignIcons.Trash, "Review removals", tint = Paper, modifier = Modifier.size(22.dp))
                DesignText(state.marked.size.toString(), color = Color(0xFFFFC4BF), size = 12, weight = FontWeight.Medium)
            }
        }
    }
    if (showVideo) VideoPreview(photo, model.videoPositions,
        markedForRemoval = ReviewRules.record(photo, state.records)?.decision == Decision.REMOVE) { choice ->
        showVideo = false
        choice?.let { model.decide(photo, if (it) Decision.REMOVE else Decision.KEEP) }
    }
    if (guide) DesignSheet("One photo. One swipe.", "↑ Keep — leave it in its month.\n↓ Remove — review before deleting.\n\nPinch with two fingers to look closer. Release to reset.\n\nTap ↶ to undo.", dismiss = { guide = false }) {
        DesignButton("Got it", Modifier.fillMaxWidth().padding(top = 8.dp)) { guide = false }
    }
}
@Composable private fun DirectionButton(label: String, icon: ImageVector, modifier: Modifier, enabled: Boolean, click: () -> Unit) {
    Row(modifier.height(36.dp).clip(CircleShape).background(Color(0x801C1C1E)).border(1.dp, Color.White.copy(alpha = .15f), CircleShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = click).padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, Modifier.size(15.dp), tint = Paper); DesignText(label, size = 12, color = Color.White, weight = FontWeight(550))
    }
}

@Composable private fun SwipeStamp(keep: Boolean, modifier: Modifier) {
    Box(modifier.clearAndSetSemantics { }.clip(RoundedCornerShape(20.dp))
        .background(if (keep) Color(0xD9007AFF) else Color(0xD9D92D23))
        .border(1.dp, Color.White.copy(alpha = .52f), RoundedCornerShape(20.dp)).padding(horizontal = 20.dp, vertical = 15.dp)) {
        DesignText(if (keep) "↑ Keep" else "↓ Remove", size = 22, color = Color.White)
    }
}
