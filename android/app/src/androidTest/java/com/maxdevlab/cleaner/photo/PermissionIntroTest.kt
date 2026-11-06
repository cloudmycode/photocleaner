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
        compose.onNodeWithText("Make room for more").assertExists()
        compose.onNodeWithText("1  Find extra copies").assertExists()
        compose.onNodeWithText("Nothing is deleted without your confirmation.").performScrollTo().assertIsDisplayed()
        Assert.assertEquals(0, requests)
        compose.onNodeWithText("Allow media access").assertIsDisplayed().performClick()
        Assert.assertEquals(1, requests)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        android.os.SystemClock.sleep(1_000)
        UiDevice.getInstance(instrumentation).takeScreenshot(File(dir, "13-permission-intro.png"))
    }
}
