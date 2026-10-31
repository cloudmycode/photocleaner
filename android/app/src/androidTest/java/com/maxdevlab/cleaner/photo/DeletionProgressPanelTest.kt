package com.maxdevlab.cleaner.photo

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maxdevlab.cleaner.photo.data.*
import com.maxdevlab.cleaner.photo.ui.*
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeletionProgressPanelTest {
    @get:Rule val compose = createComposeRule()
    @Test fun showsRealCountsBytesAndDifferentWaitingState() {
        var progress by mutableStateOf(DeleteProgress(DeletePhase.DELETING, 40, 12, 11, 1, 8L * 1024 * 1024))
        compose.setContent {
            CleanerTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { DeletionProgressPanel(progress) } }
        }
        compose.onNodeWithText("Deleting photos…").assertIsDisplayed()
        compose.onNodeWithText("30%").assertIsDisplayed()
        compose.onNodeWithText("12 of 40 processed").assertIsDisplayed()
        compose.onNodeWithText("${PhotoDetailFormat.size(progress.freedBytes)} freed").assertIsDisplayed()
        compose.onNodeWithText("1 photo could not be deleted").assertIsDisplayed()
        val output = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "deletion-progress.png")
        output.outputStream().use { compose.onNodeWithTag("deletion-progress").captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.runOnIdle { progress = progress.copy(phase = DeletePhase.WAITING_FOR_SYSTEM) }
        compose.onNodeWithText("Waiting for Android…").assertIsDisplayed()
        compose.onNodeWithText("30%").assertDoesNotExist()
        compose.runOnIdle { progress = DeleteProgress(DeletePhase.FINISHING, 40, 40, 40, 0, 32L * 1024 * 1024) }
        compose.onNodeWithText("Updating library…").assertIsDisplayed()
        compose.onNodeWithText("100%").assertIsDisplayed()
    }
}
