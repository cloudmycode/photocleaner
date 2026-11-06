package com.maxdevlab.cleaner.photo

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.*
import com.maxdevlab.cleaner.photo.data.*
import com.maxdevlab.cleaner.photo.platform.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoReviewTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val media = TestPhotoFixture(context)
    private val fixture = object : ExternalResource() {
        override fun before() {
            check(BuildConfig.ISOLATED_TEST_BUILD && context.packageName.endsWith(".devicetest"))
            check(Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
            UiDevice.getInstance(instrumentation).apply { wakeUp(); executeShellCommand("wm dismiss-keyguard") }
            (context.applicationContext as PhotoCleanerApplication).cleanupAds = TestCleanupAds()
            ReviewDatabase(context).let { try { it.clear() } finally { it.close() } }
            DeletionJournal(context).let { try { it.clear() } finally { it.close() } }
            media.create(); media.addVideo()
        }
        override fun after() { media.cleanRegisteredPhotos() }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)).around(fixture).around(compose)
    private fun model() = ViewModelProvider(compose.activity)[PhotoViewModel::class.java]
    private fun waitFor(text: String) {
        try {
            compose.waitUntil(30_000) { compose.onAllNodes(hasText(text) or hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            val directory = File(context.getExternalFilesDir(null), "video-review").apply { mkdirs() }
            UiDevice.getInstance(instrumentation).apply {
                takeScreenshot(File(directory, "failure.png"))
                dumpWindowHierarchy(File(directory, "failure.xml"))
            }
            throw failure
        }
    }
    private fun click(text: String) {
        val action = (hasText(text) or hasContentDescription(text)) and isEnabled()
        compose.waitUntil(30_000) { compose.onAllNodes(action).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(action).assertIsDisplayed().performClick()
    }
    @Test fun videoPlaybackFilterUndoAndMixedDeletion() {
        waitFor("Tidy It")
        compose.onNodeWithText("Months").performClick()
        val device = UiDevice.getInstance(instrumentation)
        device.wait(Until.findObject(By.pkg("com.android.systemui").text("Got it")), 1500)?.click()
        compose.waitUntil(30_000) { !model().state.value.loading }
        assertEquals(4, model().state.value.photos.size)
        assertEquals(1, model().state.value.photos.count { it.isVideo })
        click("Videos")
        assertEquals(1, model().state.value.groups.single().photos.size)
        click("Start sorting")
        click("Play video")
        waitFor("Pause video")
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("Pause video") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        click("Pause video"); click("Mute"); click("Unmute"); click("Play video")
        val dir = File(context.getExternalFilesDir(null), "video-review").apply { mkdirs() }
        device.takeScreenshot(File(dir, "video-player.png"))
        click("Close video")
        assertTrue(model().state.value.marked.isEmpty())
        assertEquals(1, model().state.value.remaining.size)
        click("Play video"); waitFor("Pause video")
        click("Mark for removal")
        compose.activityRule.scenario.recreate()
        waitFor("Close video")
        compose.onNodeWithText("Mark for removal").assertIsOn()
        assertTrue(model().state.value.marked.isEmpty()) // Staged until the preview closes.
        click("Mark for removal")
        compose.onNodeWithText("Mark for removal").assertIsOff()
        click("Mark for removal"); click("Close video"); waitFor("Month, sorted.")
        assertEquals(1, model().state.value.marked.size)
        click("Undo last swipe"); waitFor("1 / 1")
        assertTrue(model().state.value.marked.isEmpty())
        compose.onNodeWithContentDescription("Back to months", useUnmergedTree = true).performClick()
        click("All"); click("Start sorting")
        assertEquals(3, model().state.value.remaining.size)
        click("Play video"); waitFor("Pause video"); click("Mark for removal"); click("Close video")
        compose.waitUntil(30_000) { !model().state.value.busy && model().state.value.remaining.firstOrNull()?.isVideo == false }
        compose.runOnIdle { model().decide(model().state.value.remaining.first(), Decision.REMOVE) }
        compose.waitUntil(30_000) { !model().state.value.busy && model().state.value.marked.size == 2 }
        compose.runOnIdle { model().navigate("trash") }
        click("Play video"); waitFor("Pause video")
        compose.onNodeWithText("Mark for removal").assertIsOn()
        click("Mark for removal"); compose.onNodeWithText("Mark for removal").assertIsOff()
        click("Mark for removal"); click("Close video")
        click("Select all"); click("Delete · 2"); waitFor("Delete 1 photo · 1 video?")
        click("Cancel"); assertEquals(4, model().state.value.photos.size)
        click("Delete · 2"); click("Delete media")
        // Fixtures are owned by this isolated app, so consent may be granted without a system dialog.
        device.wait(Until.findObject(By.res("android", "button1")), 2000)?.click()
        compose.waitUntil(30_000) { model().state.value.deletedCount == 2 && !model().state.value.busy }
        assertEquals(2, model().state.value.photos.size)
        assertTrue(model().state.value.photos.none { it.isVideo })
    }
    @Test fun uncheckingVideoPreviewKeepsMediaInMonthlyAndRemoveFlows() {
        waitFor("Tidy It")
        compose.onNodeWithText("Months").performClick()
        UiDevice.getInstance(instrumentation).wait(Until.findObject(By.pkg("com.android.systemui").text("Got it")), 1500)?.click()
        compose.waitUntil(30_000) { !model().state.value.loading }
        click("Videos"); click("Start sorting"); click("Play video"); waitFor("Pause video")
        click("Mark for removal"); click("Mark for removal"); click("Close video")
        waitFor("Month, sorted.")
        val photo = model().state.value.photos.single { it.isVideo }
        assertEquals(Decision.KEEP, ReviewRules.record(photo, model().state.value.records)?.decision)
        click("Undo last swipe"); waitFor("1 / 1")
        click("Play video"); waitFor("Pause video"); click("Mark for removal"); click("Close video")
        waitFor("Month, sorted.")
        compose.runOnIdle { model().navigate("trash") }
        click("Play video"); waitFor("Pause video")
        compose.onNodeWithText("Mark for removal").assertIsOn()
        click("Mark for removal")
        compose.activityRule.scenario.recreate(); waitFor("Close video")
        compose.onNodeWithText("Mark for removal").assertIsOff()
        click("Close video"); waitFor("Nothing to remove")
        assertEquals(Decision.KEEP, ReviewRules.record(photo, model().state.value.records)?.decision)
        assertEquals(4, model().state.value.photos.size)
    }

}
