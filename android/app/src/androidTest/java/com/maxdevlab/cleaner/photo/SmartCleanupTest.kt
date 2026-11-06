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
class SmartCleanupTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val photos = TestPhotoFixture(context)
    private val fixture = object : ExternalResource() {
        override fun before() {
            check(BuildConfig.ISOLATED_TEST_BUILD && context.packageName.endsWith(".devicetest"))
            check(Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
            UiDevice.getInstance(instrumentation).apply { wakeUp(); executeShellCommand("wm dismiss-keyguard") }
            (context.applicationContext as PhotoCleanerApplication).cleanupAds = TestCleanupAds()
            ReviewDatabase(context).let { try { it.clear() } finally { it.close() } }
            DeletionJournal(context).let { try { it.clear() } finally { it.close() } }
            photos.create(count = 8, smartCleanup = true)
        }
        override fun after() { photos.cleanRegisteredPhotos() }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES)).around(fixture).around(compose)
    private fun waitFor(text: String) = compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun choose(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(text) and isEnabled()).performClick()
    }
    private fun model(): PhotoViewModel = ViewModelProvider(compose.activity)[PhotoViewModel::class.java]
    private fun start() {
        waitFor("Tidy It")
        UiDevice.getInstance(instrumentation).wait(Until.findObject(By.pkg("com.android.systemui").text("Got it")), 1500)?.click()
        UiDevice.getInstance(instrumentation).findObject(By.text("Wait"))?.click()
        compose.waitUntil(30_000) { !model().state.value.loading }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        UiDevice.getInstance(instrumentation).findObject(By.text("Wait"))?.click()
        android.os.SystemClock.sleep(600)
        val dir = File(context.getExternalFilesDir(null), "smart-cleanup").apply { mkdirs() }
        UiDevice.getInstance(instrumentation).takeScreenshot(File(dir, "$name.png"))
    }
    @Test fun quickCleanupIsDefaultAndMonthlySortingRemainsAccessible() {
        start()
        assertEquals("smart", model().state.value.screen)
        compose.waitUntil(30_000) { model().state.value.scanComplete }
        val extras = model().state.value.cleanup(CleanupKind.DUPLICATE).flatMap { it.extras }.distinctBy { it.uri }
        compose.onNodeWithText("Up to ${formatBytes(extras.sumOf { it.bytes })} to free up").assertExists()
        compose.onNodeWithText("Review duplicates").performClick()
        waitFor("★ Best kept by default")
        assertEquals(8, photos.count()) // A recommendation never deletes without review and confirmation.
        compose.activityRule.scenario.recreate()
        waitFor("★ Best kept by default")
        compose.onNodeWithContentDescription("Back to cleanup", useUnmergedTree = true).performClick()
        waitFor("Review duplicates")
        compose.onNodeWithText("Months").performClick()
        waitFor("Start sorting")
        assertEquals("home", model().state.value.screen)
        compose.runOnIdle { model().back() }
        waitFor("Review duplicates")
        assertEquals("smart", model().state.value.screen)
        capture("quick-cleanup-home")
    }
    @Test fun screenshotReviewUndoAndRecreationUseExistingMonthlyFlow() {
        start()
        assertEquals(2, model().state.value.screenshots.size)
        compose.onNodeWithText("Screenshots").performClick()
        waitFor("Keep what matters."); capture("screenshots")
        compose.onNodeWithText("Start sorting").performClick()
        waitFor("1 / 2")
        assertTrue(model().state.value.remaining.all { it.isScreenshot })
        choose("Remove")
        waitFor("2 / 2")
        compose.onNodeWithContentDescription("Undo last swipe", useUnmergedTree = true).performClick()
        waitFor("1 / 2")
        assertTrue(model().state.value.marked.isEmpty())
        choose("Remove"); waitFor("2 / 2")
        compose.activityRule.scenario.recreate(); waitFor("2 / 2")
        assertEquals("screenshots", model().state.value.reviewSource)
        assertTrue(model().state.value.remaining.single().isScreenshot)
        choose("Keep"); waitFor("Screenshots, sorted.")
        compose.onNodeWithText("Review removals").performClick(); waitFor("Select all")
        compose.onNodeWithText("Select all").performClick(); compose.onNodeWithContentDescription("Keep selected").performClick()
        waitFor("Nothing to remove"); assertTrue(model().state.value.marked.isEmpty())
    }
    @Test fun screenshotHomeShowsCompletionEvenWithOtherPhotosPending() {
        start()
        compose.onNodeWithText("Screenshots").performClick()
        waitFor("Start sorting")
        compose.onNodeWithText("Start sorting").performClick()
        waitFor("1 / 2"); choose("Keep")
        waitFor("2 / 2"); choose("Keep")
        waitFor("Screenshots, sorted.")
        compose.onNodeWithContentDescription("Back to months").performClick()
        waitFor("All screenshots sorted")
        compose.onNodeWithText("Keep what matters.").assertDoesNotExist()
        compose.onNodeWithText("Review again").performClick()
        waitFor("1 / 2")
    }
    @Test fun groupsScanPreviewCancelAndDeleteReuseExistingResult() {
        start()
        compose.onNodeWithText("Clean up").performClick()
        compose.waitUntil(30_000) { model().state.value.scanComplete }
        val state = model().state.value
        assertTrue(state.cleanup(CleanupKind.DUPLICATE).isNotEmpty())
        assertTrue(state.cleanup(CleanupKind.BURST).isNotEmpty())
        capture("smart-home")
        compose.onNodeWithText("Burst photos").performScrollTo().performClick()
        waitFor("★ Best kept by default")
        capture("bursts")
        compose.onNodeWithContentDescription("Back to cleanup", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Duplicate photos").performScrollTo().performClick()
        waitFor("★ Best kept by default")
        val extra = state.cleanup(CleanupKind.DUPLICATE).first().extras.first()
        compose.runOnIdle { model().requestDelete(setOf(extra.uri), setOf(PhotoIdentity(extra.uri, "outdated-content"))) }
        assertFalse(model().state.value.busy)
        assertEquals(8, photos.count())
        val count = state.cleanup(CleanupKind.DUPLICATE).sumOf { it.extras.size }
        waitFor("Delete ${photoCount(count)}")
        compose.onNodeWithText("Deselect extras").performClick()
        compose.onNodeWithText("Delete 0 photos").assertIsNotEnabled()
        compose.runOnIdle { assertTrue("Deselection must be retained", model().cleanupSelection.selected.isEmpty()) }
        compose.activityRule.scenario.recreate()
        waitFor("Delete 0 photos")
        compose.onNodeWithText("Delete 0 photos").assertIsNotEnabled()
        compose.onNodeWithText("Select extras").performClick()
        waitFor("Delete ${photoCount(count)}")
        capture("duplicates")
        // Open the first visible thumbnail and move to the next preview.
        compose.onNodeWithContentDescription("Preview ${state.cleanup(CleanupKind.DUPLICATE).first().photos.first().name}").performClick()
        compose.onNodeWithContentDescription("Next photo", useUnmergedTree = true).performClick()
        val selection = model().cleanupSelection.selected
        compose.onNodeWithText("Mark for removal").performClick()
        compose.runOnIdle { assertNotEquals(selection, model().cleanupSelection.selected) }
        compose.onNodeWithText("Mark for removal").performClick()
        compose.runOnIdle { assertEquals(selection, model().cleanupSelection.selected) }
        compose.onNodeWithContentDescription("Close preview", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Delete ${photoCount(count)}").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(8, photos.count())
        compose.onNodeWithText("Delete ${photoCount(count)}").performClick()
        compose.onNodeWithText("Delete photos").performClick()
        val device = UiDevice.getInstance(instrumentation)
        val cancel = device.wait(Until.findObject(By.res("android", "button2")), 10000)
        assertNotNull(cancel); cancel.click()
        compose.waitUntil(20_000) { !model().state.value.busy }
        assertEquals(8, photos.count())
        assertEquals("duplicates", model().state.value.screen)
        compose.onNodeWithText("Delete ${photoCount(count)}").performClick()
        compose.onNodeWithText("Delete photos").performClick()
        val approve = device.wait(Until.findObject(By.res("android", "button1")), 10000)
        assertNotNull(approve); approve.click()
        waitFor("Cleanup complete"); waitFor("${photoCount(count)} deleted")
        capture("deletion-result")
        assertEquals(8 - count, photos.count())
        assertEquals(count, model().state.value.deletedCount)
        assertEquals("duplicates", model().state.value.trashReturn)
        compose.runOnIdle { model().back() }
        waitFor("✓ Cleaned · 1 photo kept")
        compose.onNodeWithText("Delete 0 photos").assertIsNotEnabled()
    }
}
