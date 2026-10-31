package com.maxdevlab.cleaner.photo

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maxdevlab.cleaner.photo.data.PhotoDetailFormat
import com.maxdevlab.cleaner.photo.ui.CleanerTheme
import com.maxdevlab.cleaner.photo.ui.CleanupResultContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Exercises only the result UI with sample totals; no photo access or deletion. */
@RunWith(AndroidJUnit4::class)
class CleanupResultAnimationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun centeredCheckThenStableTotalsAndRestoredResult() {
        check(BuildConfig.ISOLATED_TEST_BUILD)
        compose.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(compose)
        val bytes = 128L * 1024 * 1024
        var done = 0
        restoration.setContent {
            CleanerTheme {
                CleanupResultContent(PhotoState(deletedCount = 24, deletedBytes = bytes,
                    deleteReportId = "animation-preview", showRatingInvitation = true),
                    back = {}, done = { done++ }, rateApp = {}, onVisible = {})
            }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val root = compose.onNodeWithTag("cleanup-result").fetchSemanticsNode().boundsInRoot
        val centered = compose.onNodeWithTag("cleanup-result-check").fetchSemanticsNode().boundsInRoot.center.y
        assertTrue("Check should start at screen center", abs(centered - root.center.y) < 2f)
        compose.onNodeWithText("Done").assertDoesNotExist()
        capture("01-centered-check")

        compose.mainClock.advanceTimeBy(1750)
        capture("02-revealing-result")
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        val settled = compose.onNodeWithTag("cleanup-result-check").fetchSemanticsNode().boundsInRoot.center.y
        assertTrue("Check should move above the result", settled < centered - 50f)
        compose.onNodeWithText(PhotoDetailFormat.size(bytes)).assertIsDisplayed()
        compose.onNodeWithText("24 photos deleted").assertIsDisplayed()
        compose.onNodeWithText("Rate Photo Cleaner").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        assertEquals(1, done)
        capture("03-complete-result")

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText(PhotoDetailFormat.size(bytes)).assertIsDisplayed()
        val restored = compose.onNodeWithTag("cleanup-result-check").fetchSemanticsNode().boundsInRoot.center.y
        assertEquals("Completed presentation must not replay", settled, restored, 2f)
    }

    private fun capture(name: String) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "result-animation").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            compose.onNodeWithTag("cleanup-result").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
