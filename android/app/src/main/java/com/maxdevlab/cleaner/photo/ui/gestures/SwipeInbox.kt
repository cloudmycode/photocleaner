package com.maxdevlab.cleaner.photo.ui.gestures

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import com.maxdevlab.cleaner.photo.data.Photo

/** One new stroke, pinned to the exact photo the person started touching. Never a queue of directions. */
@Stable internal class SwipeStroke(val photo: Photo, initial: Offset) {
    var offset by mutableStateOf(initial)
    var released by mutableStateOf(false)
    fun matches(other: Photo) = photo.uri == other.uri && photo.signature == other.signature
}

@Stable internal class SwipeInbox {
    var stroke by mutableStateOf<SwipeStroke?>(null); private set
    fun begin(photo: Photo, initial: Offset = Offset.Zero): Boolean {
        if (stroke != null) return false
        stroke = SwipeStroke(photo, initial)
        return true
    }
    fun drag(amount: Offset) { stroke?.takeUnless { it.released }?.let { it.offset += amount } }
    fun release(): SwipeStroke? = stroke?.takeUnless { it.released }?.also { it.released = true }
    fun offsetFor(photo: Photo): Offset? = stroke?.takeIf { it.matches(photo) }?.offset
    fun take(photo: Photo): SwipeStroke? = stroke?.takeIf { it.released && it.matches(photo) }?.also { stroke = null }
    fun cancelActive(): SwipeStroke? = stroke?.takeUnless { it.released }?.also { stroke = null }
    fun clear() { stroke = null }
}
