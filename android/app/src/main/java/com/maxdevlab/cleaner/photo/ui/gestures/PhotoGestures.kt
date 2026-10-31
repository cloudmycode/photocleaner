package com.maxdevlab.cleaner.photo.ui.gestures

import androidx.compose.foundation.gestures.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*

/** One owner arbitrates swipe and pinch; a pinch locks out sorting until every finger lifts. */
internal suspend fun PointerInputScope.detectPhotoGestures(
    zoomReturning: () -> Boolean,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onPinchStart: () -> Unit,
    onPinch: (Offset, Offset, Float) -> Unit,
    onPinchEnd: () -> Unit,
    onDown: () -> Unit = {},
    onEnd: () -> Unit = {},
    onTap: () -> Unit = {}
) = awaitEachGesture {
    val down = awaitFirstDown()
    onDown()
    var locked = zoomReturning()
    var dragging = false
    var pinching = false
    var finished = false
    var distance = Offset.Zero
    try {
        do {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) break
            val fingers = event.changes.count { it.pressed }
            if (fingers >= 2) {
                locked = true
                if (!pinching) { onPinchStart(); pinching = true; dragging = false }
                // Exclude new/lifted fingers so changing pointer count never shifts the focus.
                if (event.changes.count { it.pressed && it.previousPressed } >= 2) {
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    onPinch(event.calculateCentroid(false) - centre, event.calculateCentroid(true) - centre, event.calculateZoom())
                }
                event.changes.forEach { it.consume() }
            } else if (locked) {
                if (pinching) { onPinchEnd(); pinching = false }
                event.changes.forEach { it.consume() }
            } else {
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.pressed) {
                    val delta = change.positionChange()
                    distance += delta
                    if (!dragging && distance.getDistance() > viewConfiguration.touchSlop) {
                        dragging = true
                        onDrag(distance * (1f - viewConfiguration.touchSlop / distance.getDistance()))
                    } else if (dragging) onDrag(delta)
                    if (dragging) change.consume()
                }
            }
            if (fingers == 0) {
                finished = true
                val up = event.changes.firstOrNull { it.id == down.id }
                // Only a short, unconsumed single-finger tap can dismiss details.
                // Drags, canceled touches and a remaining finger after a pinch cannot.
                if (!dragging && !locked && up?.changedToUp() == true &&
                    (up.position - down.position).getDistance() <= viewConfiguration.touchSlop &&
                    up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                    up.consume()
                    onTap()
                }
                break
            }
        } while (true)
    } finally {
        if (pinching) onPinchEnd()
        if (dragging) { if (finished && !locked) onDragEnd() else onDragCancel() }
        onEnd()
    }
}
