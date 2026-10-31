package com.maxdevlab.cleaner.photo

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.maxdevlab.cleaner.photo.ui.FittedPhotoShape
import com.maxdevlab.cleaner.photo.ui.fittedPhotoBounds
import org.junit.Assert.*
import org.junit.Test

class FittedPhotoShapeTest {
    @Test fun cornersFollowTheImageInsteadOfItsLetterbox() {
        val stage = Size(1000f, 1600f)
        assertEquals(Rect(0f, 425f, 1000f, 1175f), fittedPhotoBounds(stage, 4000, 3000))
        assertEquals(Rect(100f, 0f, 900f, 1600f), fittedPhotoBounds(stage, 2000, 4000))
        val outline = FittedPhotoShape(4000, 3000).createOutline(stage, LayoutDirection.Ltr, Density(2f)) as Outline.Rounded
        assertEquals(425f, outline.roundRect.top, 0f)
        assertEquals(24f, outline.roundRect.topLeftCornerRadius.x, 0f)
    }
    @Test fun unknownDimensionsUseTheStageWithoutInvalidCoordinates() {
        assertEquals(Rect(0f, 0f, 500f, 800f), fittedPhotoBounds(Size(500f, 800f), 0, 0))
        assertEquals(Rect.Zero, fittedPhotoBounds(Size.Zero, 4000, 3000))
    }
}
