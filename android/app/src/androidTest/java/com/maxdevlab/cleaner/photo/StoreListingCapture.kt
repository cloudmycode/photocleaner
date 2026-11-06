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
import com.maxdevlab.cleaner.photo.ads.CleanupAdSession
import com.maxdevlab.cleaner.photo.data.*
import com.maxdevlab.cleaner.photo.platform.DeletionJournal
import java.io.File
import org.junit.Test
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Opt-in real UI capture in an isolated emulator with generated sample media. */
@RunWith(AndroidJUnit4::class)
class StoreListingCapture {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val compose = createAndroidComposeRule<MainActivity>()
    private val media = StoreListingMedia(context)
    private val device get() = UiDevice.getInstance(instrumentation)
    private fun model() = ViewModelProvider(compose.activity)[PhotoViewModel::class.java]
    private val fixture = object : ExternalResource() {
        override fun before() {
            check(BuildConfig.ISOLATED_TEST_BUILD && context.packageName.endsWith(".devicetest"))
            check(Build.VERSION.SDK_INT >= 33)
            check(Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
            check(InstrumentationRegistry.getArguments().getString("storeListingCapture") == "true")
            device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
            (context.applicationContext as PhotoCleanerApplication).apply {
                cleanupAds = TestCleanupAds(); cleanupAdSession = CleanupAdSession()
            }
            ReviewDatabase(context).let { try { it.clear() } finally { it.close() } }
            DeletionJournal(context).let { try { it.clear() } finally { it.close() } }
            context.getSharedPreferences("rating_invitation", 0).edit().clear().commit()
            media.create()
        }
        override fun after() { media.clean() }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)).around(fixture).around(compose)
    private fun waitFor(label: String) {
        compose.waitUntil(45_000) { compose.onAllNodes(hasText(label) or hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun click(label: String) {
        val matcher = (hasText(label) or hasContentDescription(label)) and isEnabled()
        compose.waitUntil(45_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(matcher).performClick()
    }
    private fun capture(name: String) {
        compose.waitForIdle(); instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(1_500)
        val dir = File(context.getExternalFilesDir(null), "store-listing").apply { mkdirs() }
        check(device.takeScreenshot(File(dir, "$name.png")))
    }
    @Test fun capture() {
        waitFor("Tidy It")
        click("Months")
        device.wait(Until.findObject(By.pkg("com.android.systemui").text("Got it")), 1500)?.click()
        compose.waitUntil(45_000) { !model().state.value.loading && model().state.value.photos.size == 15 }
        capture("01-months")
        click("Photos"); click("Start sorting"); waitFor("1 / 3"); capture("02-swipe")
        click("Gesture guide"); waitFor("One photo. One swipe."); capture("extra-gesture-guide"); click("Got it")
        click("Back to months"); click("All"); click("Clean up")
        compose.waitUntil(90_000) { model().state.value.scanComplete && !model().state.value.scanning }
        check(model().state.value.cleanup(CleanupKind.DUPLICATE).isNotEmpty())
        check(model().state.value.cleanup(CleanupKind.BURST).isNotEmpty())
        capture("extra-cleanup-home")
        click("Duplicate photos"); waitFor("★ Best kept by default"); capture("03-duplicates")
        click("Back to cleanup"); click("Burst photos"); waitFor("★ Best kept by default"); capture("04-bursts")
        click("Back to cleanup"); click("Screenshots"); waitFor("Keep what matters."); capture("05-screenshots")
        click("Months"); click("Videos"); click("Start sorting"); click("Play video")
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("Pause video") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        android.os.SystemClock.sleep(2_000)
        click("Pause video"); click("Mark for removal"); capture("06-video")
        click("Close video"); waitFor("Month, sorted.")
        compose.runOnIdle { model().navigate("home") }
        click("All"); click("Continue sorting")
        repeat(3) { index ->
            compose.waitUntil(30_000) { !model().state.value.busy && model().state.value.remaining.firstOrNull()?.isVideo == false }
            click(if (index < 2) "Remove" else "Keep")
        }
        waitFor("Month, sorted."); click("Review removals"); click("Select all"); waitFor("Delete · 3")
        capture("07-review")
        // Demonstrate revising the final choice in the actual preview: keep the video.
        click("Play video"); click("Mark for removal"); click("Close video")
        click("Delete · 2"); click("Delete photos")
        device.wait(Until.findObject(By.res("android", "button1")), 2500)?.click()
        compose.waitUntil(45_000) { model().state.value.deletedCount == 2 && !model().state.value.busy }
        waitFor("Cleanup complete"); capture("08-space")
    }
}
