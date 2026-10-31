package com.maxdevlab.cleaner.photo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maxdevlab.cleaner.photo.ui.gestures.*
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoGestureTest {
    @get:Rule val compose = createComposeRule()
    private val zoom = PhotoZoom()
    private var swipe = Offset.Zero
    private var sorted = 0
    private var pinches = 0
    private var taps = 0
    private val photo get() = compose.onNodeWithTag("gesture-stage")
    @Before fun showStage() {
        check(BuildConfig.ISOLATED_TEST_BUILD)
        compose.setContent {
            val scope = rememberCoroutineScope()
            Box(Modifier.size(300.dp, 400.dp).testTag("gesture-stage").pointerInput(Unit) {
                detectPhotoGestures({ zoom.active }, { swipe += it }, { sorted++; swipe = Offset.Zero }, { swipe = Offset.Zero },
                    { pinches++; zoom.begin(swipe); swipe = Offset.Zero }, zoom::move, { zoom.release(scope) },
                    onTap = { taps++ })
            }) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom.transform.scale; scaleY = scaleX
                    translationX = zoom.transform.offset.x; translationY = zoom.transform.offset.y
                }.background(Color.Blue))
            }
        }
    }
    private fun settled() {
        compose.waitUntil(10_000) { !zoom.active }
        compose.runOnIdle { Assert.assertEquals(ZoomTransform(), zoom.transform) }
    }
    @Test fun onlyShortSingleFingerTapsTriggerTapAction() {
        photo.performTouchInput { click() }
        compose.runOnIdle { Assert.assertEquals(1, taps); Assert.assertEquals(0, sorted) }
        photo.performTouchInput { down(center); advanceEventTime(800); up() }
        photo.performTouchInput { down(center); cancel() }
        photo.performTouchInput { swipeUp() }
        photo.performTouchInput { down(0, center); down(1, percentOffset(.7f, .5f)); up(1); up(0) }
        settled()
        compose.runOnIdle { Assert.assertEquals(1, taps); Assert.assertEquals(1, sorted) }
    }
    @Test fun offCentrePinchTracksFocusAndResidualFingerCannotSort() {
        var width = 0f; var height = 0f
        photo.performTouchInput {
            width = this.width.toFloat(); height = this.height.toFloat()
            down(0, percentOffset(.3f, .4f)); down(1, percentOffset(.5f, .4f))
            updatePointerTo(0, percentOffset(.2f, .4f)); updatePointerTo(1, percentOffset(.6f, .4f)); move()
        }
        compose.runOnIdle {
            Assert.assertEquals(2f, zoom.transform.scale, .01f)
            Assert.assertEquals(width * .1f, zoom.transform.offset.x, 1f)
            Assert.assertEquals(height * .1f, zoom.transform.offset.y, 1f)
        }
        photo.performTouchInput {
            updatePointerTo(0, percentOffset(.25f, .48f)); updatePointerTo(1, percentOffset(.65f, .48f)); move()
        }
        compose.runOnIdle {
            Assert.assertEquals(width * .15f, zoom.transform.offset.x, 1f)
            Assert.assertEquals(height * .18f, zoom.transform.offset.y, 1f)
        }
        photo.performTouchInput { up(1); moveTo(0, percentOffset(.3f, .9f)); up(0) }
        settled()
        Assert.assertEquals(0, sorted)
        photo.performTouchInput { swipeUp() }
        Assert.assertEquals(1, sorted)
    }
    @Test fun secondFingerTakesOverAnExistingDragAndCancelRestoresPhoto() {
        photo.performTouchInput { down(0, center); moveTo(0, percentOffset(.5f, .65f)) }
        compose.runOnIdle { Assert.assertTrue(swipe.y > 0f) }
        photo.performTouchInput {
            down(1, percentOffset(.7f, .65f))
            updatePointerTo(0, percentOffset(.3f, .65f)); updatePointerTo(1, percentOffset(.9f, .65f)); move()
        }
        compose.runOnIdle { Assert.assertEquals(1, pinches); Assert.assertTrue(zoom.transform.scale > 1.5f); Assert.assertEquals(Offset.Zero, swipe) }
        photo.performTouchInput { cancel() }
        settled(); Assert.assertEquals(0, sorted)
        photo.performTouchInput { down(center); moveTo(percentOffset(.5f, .8f)); cancel() }
        compose.runOnIdle { Assert.assertEquals(0, sorted); Assert.assertEquals(Offset.Zero, swipe) }
    }
    @Test fun quickRegripAndAdditionalFingerDoNotResetOrSort() {
        photo.performTouchInput {
            down(0, percentOffset(.3f, .5f)); down(1, percentOffset(.7f, .5f))
            updatePointerTo(0, percentOffset(.1f, .5f)); updatePointerTo(1, percentOffset(.9f, .5f)); move()
        }
        var before = ZoomTransform()
        compose.runOnIdle { before = zoom.transform }
        photo.performTouchInput { down(2, percentOffset(.5f, .7f)); up(2) }
        compose.runOnIdle { Assert.assertEquals(before, zoom.transform) }
        compose.mainClock.autoAdvance = false
        photo.performTouchInput { up(1) }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { before = zoom.transform }
        photo.performTouchInput { down(1, percentOffset(.9f, .5f)) }
        compose.runOnIdle { Assert.assertEquals(before, zoom.transform) }
        photo.performTouchInput { up(1); up(0) }
        compose.mainClock.autoAdvance = true
        settled(); Assert.assertEquals(0, sorted)
    }
}
