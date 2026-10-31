package com.maxdevlab.cleaner.photo.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

internal val PhotoCornerRadius = 12.dp

/** The same Fit geometry for image corners and the next-card reveal mask. */
internal fun fittedPhotoBounds(size: Size, width: Int, height: Int): Rect {
    if (width <= 0 || height <= 0) return Rect(0f, 0f, size.width, size.height)
    val ratio = minOf(size.width / width, size.height / height)
    val w = width * ratio; val h = height * ratio
    return Rect((size.width - w) / 2, (size.height - h) / 2, (size.width + w) / 2, (size.height + h) / 2)
}

internal data class FittedPhotoShape(val width: Int, val height: Int) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(fittedPhotoBounds(size, width, height), CornerRadius(with(density) { PhotoCornerRadius.toPx() })))
}
