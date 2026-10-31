package com.maxdevlab.cleaner.photo

import androidx.compose.ui.geometry.Offset
import com.maxdevlab.cleaner.photo.ui.gestures.*
import org.junit.Assert.*
import org.junit.Test

class PhotoZoomTest {
    @Test fun offCentreDetailStaysUnderTheFingersWhileZoomingAndPanning() {
        val before = ZoomTransform(1.5f, Offset(20f, -30f))
        val previous = Offset(-120f, 75f)
        val next = Offset(-90f, 100f)
        val detail = (previous - before.offset) / before.scale
        val after = before.around(previous, next, 2.8f)
        val mapped = detail * after.scale + after.offset
        assertEquals(next.x, mapped.x, .001f); assertEquals(next.y, mapped.y, .001f)
    }
    @Test fun scaleFollowsFingersNormallyAndResistsBeyondLimits() {
        assertEquals(1f, elasticZoom(1f), 0f); assertEquals(2.5f, elasticZoom(2.5f), 0f)
        assertTrue(elasticZoom(.25f) in .85f..1f)
        assertTrue(elasticZoom(8f) in 4f..4.5f)
        assertTrue(elasticZoom(4.1f) < elasticZoom(5f))
    }
    @Test fun regrippingAtAnElasticLimitDoesNotJump() {
        for (multiplier in listOf(.4f, 7f)) {
            val zoom = PhotoZoom()
            zoom.begin(Offset.Zero); zoom.move(Offset(100f, 50f), Offset(100f, 50f), multiplier)
            val before = zoom.transform
            zoom.begin(Offset.Zero); zoom.move(Offset(100f, 50f), Offset(100f, 50f), 1f)
            assertEquals(before.scale, zoom.transform.scale, .0001f)
            assertEquals(before.offset.x, zoom.transform.offset.x, .001f)
        }
    }
}
