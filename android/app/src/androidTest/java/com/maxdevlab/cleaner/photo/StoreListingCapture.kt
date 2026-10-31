package com.maxdevlab.cleaner.photo

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.maxdevlab.cleaner.photo.ads.CleanupAdSession
import com.maxdevlab.cleaner.photo.data.*
import com.maxdevlab.cleaner.photo.platform.DeletionJournal
import java.io.File
import org.junit.Test
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Opt-in capture of the real UI with generated sample photos, never the user's library. */
@RunWith(AndroidJUnit4::class)
class StoreListingCapture {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val compose = createAndroidComposeRule<MainActivity>()
    private val photos = TestPhotoFixture(context)
    private val fixture = object : ExternalResource() {
        override fun before() {
            check(BuildConfig.ISOLATED_TEST_BUILD && context.packageName.endsWith(".devicetest"))
            check(InstrumentationRegistry.getArguments().getString("storeListingCapture") == "true")
            check(InstrumentationRegistry.getArguments().getString("allowPhysicalDevice") == "true")
            (context.applicationContext as PhotoCleanerApplication).apply {
                cleanupAds = TestCleanupAds()
                cleanupAdSession = CleanupAdSession()
            }
            val db = ReviewDatabase(context)
            try { db.clear() } finally { db.close() }
            val journal = DeletionJournal(context)
            try { journal.clear() } finally { journal.close() }
            context.getSharedPreferences("rating_invitation", 0).edit().clear().commit()
            photos.create(count = 40, storeListing = true)
        }
        override fun after() { photos.cleanRegisteredPhotos() }
    }
    private val permissions = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(*permissions))
        .around(fixture).around(compose)
    private fun waitFor(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(1_500)
        val dir = File(context.getExternalFilesDir(null), "store-listing").apply { mkdirs() }
        check(UiDevice.getInstance(instrumentation).takeScreenshot(File(dir, "$name.png")))
    }
    @Test fun capture() {
        waitFor("Start sorting")
        capture("01-months")
        compose.onNodeWithText("Start sorting").performClick()
        waitFor("1 / 8")
        capture("02-swipe")
        compose.onNodeWithContentDescription("Gesture guide").performClick()
        waitFor("One photo. One swipe.")
        capture("03-guide")
        compose.onNodeWithText("Got it").performClick()
        repeat(8) { index ->
            waitFor("${index + 1} / 8")
            if (index == 1) capture("03-full-photo")
            val label = if (index == 0 || index == 6) "Keep" else "Remove"
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(label) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(label).performClick()
        }
        waitFor("Month, sorted.")
        capture("04-month-complete")
        compose.onNodeWithText("Review removals").performClick()
        waitFor("Select all")
        compose.onNodeWithText("Select all").performClick()
        waitFor("Delete · 6")
        capture("05-review-removals")
        compose.onNodeWithText("Delete · 6").performClick()
        // The publishing capture currently targets the authorized API 27 Huawei device.
        check(Build.VERSION.SDK_INT == 27)
        waitFor("Delete photos")
        compose.onNodeWithText("Delete photos").performClick()
        waitFor("Cleanup complete")
        waitFor("6 photos deleted")
        capture("06-cleanup-complete")
    }
}
