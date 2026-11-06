package com.maxdevlab.cleaner.photo.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maxdevlab.cleaner.photo.PhotoState
import com.maxdevlab.cleaner.photo.PhotoViewModel
import com.maxdevlab.cleaner.photo.data.PhotoDetailFormat
import com.maxdevlab.cleaner.photo.data.photoCount
import kotlin.math.*

private const val CheckDuration = 1500
private const val LiftDuration = 480
private const val RevealStart = CheckDuration + LiftDuration
private const val RevealDuration = 420
private const val RevealStagger = 60
private const val ResultDuration = RevealStart + RevealStagger * 4 + RevealDuration

@Composable internal fun DeletionResultScreen(state: PhotoState, model: PhotoViewModel, rateApp: () -> Unit) {
    CleanupResultContent(state, model::back, { model.navigate(when (state.trashReturn) { "duplicates", "bursts", "smart" -> "smart"; "screenshots" -> "screenshots"; "review", "finish" -> state.reviewSource; else -> "home" }) }, rateApp) {
        state.deleteReportId?.let(model::cleanupResultVisible)
    }
}

/** One timeline: centered completion effect, icon lift, then staggered result content. */
@Composable internal fun CleanupResultContent(
    state: PhotoState,
    back: () -> Unit,
    done: () -> Unit,
    rateApp: () -> Unit,
    onVisible: () -> Unit,
) {
    var finished by rememberSaveable(state.deleteReportId) { mutableStateOf(false) }
    val elapsed = remember(state.deleteReportId) { Animatable(if (finished) ResultDuration.toFloat() else 0f) }
    var screenCenter by remember { mutableStateOf<Float?>(null) }
    var iconCenter by remember { mutableStateOf<Float?>(null) }
    val positioned = screenCenter != null && iconCenter != null
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val notifyVisible by rememberUpdatedState(onVisible)
    LaunchedEffect(state.deleteReportId, lifecycle, positioned) {
        if (!positioned) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            withFrameNanos { }
            notifyVisible()
            // Resume from the current position after backgrounding; never replay a completed result.
            if (!finished) {
                elapsed.animateTo(ResultDuration.toFloat(), tween(
                    (ResultDuration - elapsed.value).roundToInt().coerceAtLeast(0), easing = LinearEasing))
                finished = true
            }
        }
    }
    Column(Modifier.fillMaxSize().background(Paper).testTag("cleanup-result")
        .onGloballyPositioned { screenCenter = it.positionInRoot().y + it.size.height / 2f },
        horizontalAlignment = Alignment.CenterHorizontally) {
        PageHeader("Cleanup", back = back)
        Column(Modifier.weight(1f).fillMaxWidth()
            .verticalScroll(rememberScrollState(), enabled = finished).padding(25.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            // Measure a stationary slot, not the translated icon, to avoid layout feedback.
            Box(Modifier.size(130.dp).onGloballyPositioned {
                iconCenter = it.positionInRoot().y + it.size.height / 2f
            }) {
                CompletionCheck({ elapsed.value }, Modifier.fillMaxSize().graphicsLayer {
                    alpha = if (positioned) 1f else 0f
                    translationY = ((screenCenter ?: 0f) - (iconCenter ?: 0f)) *
                        (1f - resultProgress(elapsed.value, CheckDuration, LiftDuration))
                }.testTag("cleanup-result-check"))
            }
            ResultReveal(elapsed, 0, finished) {
                DesignText("SPACE FREED", size = 11, color = Muted, spacing = 1.8f)
                Spacer(Modifier.height(12.dp))
                DesignText(PhotoDetailFormat.size(state.deletedBytes.coerceAtLeast(0)),
                    size = 46, color = Accent, weight = FontWeight.SemiBold, spacing = -1f)
            }
            Spacer(Modifier.height(10.dp))
            ResultReveal(elapsed, 1, finished) {
                DesignText("${if (state.deletedVideoCount > 0) "${photoCount((state.deletedCount - state.deletedVideoCount).coerceAtLeast(0))} · ${state.deletedVideoCount} videos" else photoCount(state.deletedCount.coerceAtLeast(0))} deleted", color = Muted)
            }
            Spacer(Modifier.height(16.dp))
            ResultReveal(elapsed, 2, finished) {
                DesignText("Cleanup complete", size = 25, weight = FontWeight.Medium)
                if (state.deleteFailedCount > 0) {
                    Spacer(Modifier.height(10.dp))
                    DesignText("${state.deleteFailedCount} items could not be deleted.", color = Muted)
                }
                if (state.deleteCanceledCount > 0) {
                    Spacer(Modifier.height(10.dp))
                    DesignText("${state.deleteCanceledCount} items kept after cancellation.", color = Muted)
                }
            }
            Spacer(Modifier.height(28.dp))
            if (state.showRatingInvitation) {
                ResultReveal(elapsed, 3, finished, Modifier.fillMaxWidth().heightIn(min = 150.dp)) {
                    RatingInvitationCard(!state.busy) { if (finished) rateApp() }
                }
            }
            ResultReveal(elapsed, 4, finished, Modifier.fillMaxWidth().padding(top = 16.dp)) {
                DesignButton("Done", Modifier.fillMaxWidth(), enabled = !state.busy,
                    background = Soft, color = Ink) { if (finished) done() }
            }
        }
    }
}

private fun resultProgress(time: Float, start: Int, duration: Int): Float =
    FastOutSlowInEasing.transform(((time - start) / duration).coerceIn(0f, 1f))

/** Keep final layout measured; animate only rendering, without per-frame layout or text changes. */
@Composable private fun ResultReveal(
    elapsed: Animatable<Float, AnimationVector1D>,
    order: Int,
    finished: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.graphicsLayer {
        val progress = resultProgress(elapsed.value, RevealStart + order * RevealStagger, RevealDuration)
        alpha = progress
        translationY = 24.dp.toPx() * (1f - progress)
    }.then(if (finished) Modifier else Modifier.clearAndSetSemantics { }),
        horizontalAlignment = Alignment.CenterHorizontally, content = content)
}

@Composable private fun CompletionCheck(time: () -> Float, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val t = resultProgress(time(), 0, CheckDuration)
            drawCircle(Accent.copy(alpha = .08f), size.minDimension * .31f)
            for (i in 0 until 16) {
                val phase = (t * 2.5f + i / 16f) % 1f
                val angle = i * PI.toFloat() / 8
                val direction = Offset(cos(angle), sin(angle))
                val radius = size.minDimension * (.32f + .15f * phase)
                drawLine(Accent.copy(alpha = (1 - phase) * (1 - t) * .7f),
                    center + direction * radius, center + direction * (radius + 7.dp.toPx()),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Icon(DesignIcons.Check, null, Modifier.size(42.dp), tint = Accent)
    }
}
