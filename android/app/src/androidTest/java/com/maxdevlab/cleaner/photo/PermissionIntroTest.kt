package com.maxdevlab.cleaner.photo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.maxdevlab.cleaner.photo.ui.*
import java.io.File
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionIntroTest {
    @get:Rule val compose = createComposeRule()
    private fun waitForDemo() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("↑  Keep") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun demonstrateBeforeRequestingPhotoAccess() {
        check(BuildConfig.ISOLATED_TEST_BUILD)
        var requests = 0
        compose.setContent {
            CleanerTheme {
                Surface(Modifier.fillMaxSize(), color = Paper) {
                    Box(Modifier.safeDrawingPadding()) {
                        PermissionScreen({ requests++ }, {}, {}, {}, "You can allow all photos or choose specific photos.")
                    }
                }
            }
        }
        compose.onNodeWithText("Swipe to clean up").assertExists()
        compose.onNodeWithText("↑  Keep").performClick()
        waitForDemo()
        compose.onNodeWithText("Kept").assertExists()
        compose.onNodeWithText("↓  Remove").performClick()
        waitForDemo()
        compose.onNodeWithText("Review before deleting").assertExists()
        compose.onNodeWithTag("permission-demo-photo").performScrollTo().performTouchInput { swipeUp() }
        waitForDemo()
        compose.onNodeWithText("Kept").assertExists()
        compose.onNodeWithTag("permission-demo-photo").performTouchInput { swipeDown() }
        waitForDemo()
        compose.onNodeWithText("Review before deleting").assertExists()
        Assert.assertEquals(0, requests)
        compose.onNodeWithText("Allow photo access").assertIsDisplayed().performClick()
        Assert.assertEquals(1, requests)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        android.os.SystemClock.sleep(1_000)
        UiDevice.getInstance(instrumentation).takeScreenshot(File(dir, "13-permission-intro.png"))
    }
}
