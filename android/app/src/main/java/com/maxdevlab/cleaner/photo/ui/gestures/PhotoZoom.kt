package com.maxdevlab.cleaner.photo.ui.gestures

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.ln

/** Coordinates are in the stationary photo stage, relative to its centre. */
internal data class ZoomTransform(val scale: Float = 1f, val offset: Offset = Offset.Zero) {
    fun around(previousFocus: Offset, focus: Offset, nextScale: Float) = ZoomTransform(
        nextScale, focus - (previousFocus - offset) * (nextScale / scale)
    )
}
internal fun elasticZoom(raw: Float): Float = when {
    raw < 1f -> 1f - .15f * (1f - exp((raw - 1f) / .15f))
    raw > 4f -> 4f + .5f * (1f - exp(-(raw - 4f) / .5f))
    else -> raw
}

/** Updated in the layer phase only. A new pinch can interrupt the return spring. */
@Stable internal class PhotoZoom {
    var transform by mutableStateOf(ZoomTransform()); private set
    var active by mutableStateOf(false); private set
    private var rawScale = 1f
    private var returning: Job? = null

    fun begin(swipeOffset: Offset) {
        returning?.cancel()
        rawScale = when {
            transform.scale < 1f -> 1f + .15f * ln((1f - (1f - transform.scale) / .15f).coerceAtLeast(.00001f))
            transform.scale > 4f -> 4f - .5f * ln((1f - (transform.scale - 4f) / .5f).coerceAtLeast(.00001f))
            else -> transform.scale
        }
        transform = transform.copy(offset = transform.offset + swipeOffset)
        active = true
    }
    fun move(previousFocus: Offset, focus: Offset, multiplier: Float) {
        if (!multiplier.isFinite() || multiplier <= 0) return
        rawScale = (rawScale * multiplier).coerceIn(.25f, 8f)
        transform = transform.around(previousFocus, focus, elasticZoom(rawScale))
    }
    fun release(scope: CoroutineScope) {
        returning?.cancel()
        val start = transform
        returning = scope.launch {
            animate(0f, 1f, animationSpec = spring(dampingRatio = .9f, stiffness = 420f, visibilityThreshold = .001f)) { t, _ ->
                transform = ZoomTransform(1f + (start.scale - 1f) * (1f - t), start.offset * (1f - t))
            }
            transform = ZoomTransform(); rawScale = 1f; active = false
        }
    }
}
