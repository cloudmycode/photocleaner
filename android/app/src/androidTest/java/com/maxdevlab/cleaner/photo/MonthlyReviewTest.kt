package com.maxdevlab.cleaner.photo

import android.Manifest
import android.app.KeyguardManager
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.maxdevlab.cleaner.photo.ads.CleanupAdSession
import com.maxdevlab.cleaner.photo.data.*
import com.maxdevlab.cleaner.photo.platform.*
import java.io.File
import org.junit.*
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Only the isolated .devicetest app is allowed to mutate generated, allowlisted photos. */
@RunWith(AndroidJUnit4::class)
class MonthlyReviewTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ads = TestCleanupAds()
    private val testPhotos = TestPhotoFixture(context)
    private val preflight = object : ExternalResource() {
        override fun before() {
            check(BuildConfig.ISOLATED_TEST_BUILD && context.packageName.endsWith(".devicetest")) { "Build with -PisolatedDeviceTest=true" }
            val emulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")
            check(emulator || InstrumentationRegistry.getArguments().getString("allowPhysicalDevice") == "true") { "Physical-device tests require explicit allowPhysicalDevice=true" }
            UiDevice.getInstance(instrumentation).apply { wakeUp(); executeShellCommand("wm dismiss-keyguard") }
            check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Unlock the phone before testing" }
        }
    }
    private val fixture = object : ExternalResource() {
        override fun before() {
            // SQLiteOpenHelper did not implement AutoCloseable on Android 8.1.
            (context.applicationContext as PhotoCleanerApplication).cleanupAds = ads
            (context.applicationContext as PhotoCleanerApplication).cleanupAdSession = CleanupAdSession()
            context.getSharedPreferences("rating_invitation", 0).edit().clear().commit()
            context.getSharedPreferences("cleanup_feedback", 0).edit().clear().commit()
            val reviews = ReviewDatabase(context)
            try { reviews.clear() } finally { reviews.close() }
            val journal = DeletionJournal(context)
            try { journal.clear() } finally { journal.close() }
            testPhotos.create(large = InstrumentationRegistry.getArguments().getString("largePhotos") == "true")
            val photos = MediaStorePhotoSource(context).query()
            Assert.assertEquals(3, photos.size)
            Assert.assertEquals("Fixture capture dates must survive MediaStore scanning", listOf("2026-09", "2026-09", "2026-08"), photos.map { it.month })
            Assert.assertFalse(TestMediaScope(context).allows("content://media/external/images/media/-1"))
        }
        override fun after() {
            instrumentation.runOnMainSync { ads.dialog?.dismiss() }
            if (InstrumentationRegistry.getArguments().getString("keepTestPhotos") != "true") testPhotos.cleanRegisteredPhotos()
        }
    }
    private val permissions = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(preflight)
        .around(GrantPermissionRule.grant(*permissions)).around(fixture).around(compose)
    private var fullscreenHintHandled = false
    private fun waitFor(text: String) {
        compose.waitUntil(20_000) { runCatching { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        if (!fullscreenHintHandled && text == "Photo Cleaner") {
            // Android may show its one-time immersive-mode tutorial on a fresh device.
            UiDevice.getInstance(instrumentation).wait(androidx.test.uiautomator.Until.findObject(
                androidx.test.uiautomator.By.pkg("com.android.systemui").text("Got it")), 2_000)?.click()
            fullscreenHintHandled = true
        }
    }
    private fun choose(label: String) {
        compose.waitUntil(20_000) { compose.onAllNodes(hasText(label) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(label) and isEnabled()).performClick()
    }
    private fun waitForPhoto(date: String) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithContentDescription("Photo from $date").fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun capture(name: String) {
        if (name == "01-months") compose.waitUntil(20_000) {
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
                .fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        // The emulated GPU may present a frame after Compose has already published semantics.
        android.os.SystemClock.sleep(1_200)
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        check(UiDevice.getInstance(instrumentation).takeScreenshot(File(dir, "$name.png")))
    }
    @Test fun thirdCleanupShowsAdOnlyAfterDataSyncAndBeforeResults() {
        waitFor("Photo Cleaner")
        ads.ready = true
        val model = androidx.lifecycle.ViewModelProvider(compose.activity)[PhotoViewModel::class.java]
        val photos = model.state.value.photos.toList()
        photos.forEachIndexed { index, photo ->
            deleteOneForAdTest(model, photo)
            if (index < 2) {
                waitFor("Cleanup complete")
                Assert.assertEquals(0, ads.attempts)
            } else {
                compose.waitUntil(20_000) { ads.dialog?.isShowing == true }
                Assert.assertEquals("cleanupTransition", model.state.value.screen)
                Assert.assertTrue(model.state.value.busy)
                Assert.assertTrue(model.state.value.photos.isEmpty())
                Assert.assertTrue(model.state.value.records.isEmpty())
                Assert.assertEquals(photo.bytes, model.state.value.deletedBytes)
                compose.onNodeWithText("SPACE FREED").assertDoesNotExist()
                compose.onNodeWithText("Cleanup complete").assertDoesNotExist()
                Assert.assertEquals(1, ads.attempts)
                capture("33-ad-transition-test-double")
                compose.runOnIdle { ads.dialog!!.dismiss() }
                waitFor("Cleanup complete"); waitFor("1 photo deleted")
                compose.onNodeWithText(PhotoDetailFormat.size(photo.bytes)).assertExists()
                capture("34-after-ad-result")
            }
            Assert.assertEquals(index + 1, (context.applicationContext as PhotoCleanerApplication).cleanupAdSession.count)
            Assert.assertEquals(2 - index, accessibleFixtureCount())
            compose.activityRule.scenario.recreate()
            waitFor("Cleanup complete")
            Assert.assertEquals(if (index < 2) 0 else 1, ads.attempts)
        }
    }
    @Test fun unavailableAdSkipsImmediatelyAndNeverAppearsAfterLoadingLater() {
        verifyUnavailableAd(failToShow = false)
    }
    @Test fun adShowFailureStillRevealsTheSuccessfulCleanup() {
        verifyUnavailableAd(failToShow = true)
    }
    private fun verifyUnavailableAd(failToShow: Boolean) {
        waitFor("Photo Cleaner")
        compose.runOnIdle {
            val session = (context.applicationContext as PhotoCleanerApplication).cleanupAdSession
            repeat(2) { index ->
                session.record(DeleteReport("prior-$index", listOf(DeleteItem("fixture", "sig", 1, DeleteStatus.DELETED)), false))
            }
        }
        ads.failToShow = failToShow
        val model = androidx.lifecycle.ViewModelProvider(compose.activity)[PhotoViewModel::class.java]
        val photo = model.state.value.photos.first()
        deleteOneForAdTest(model, photo)
        waitFor("Cleanup complete"); waitFor("1 photo deleted")
        Assert.assertFalse(model.state.value.busy)
        Assert.assertEquals(1, ads.attempts)
        Assert.assertEquals(3, (context.applicationContext as PhotoCleanerApplication).cleanupAdSession.count)
        ads.ready = true
        compose.activityRule.scenario.recreate()
        waitFor("Cleanup complete")
        Assert.assertEquals(1, ads.attempts)
        Assert.assertNull(ads.dialog)
        Assert.assertEquals(2, accessibleFixtureCount())
    }
    private fun deleteOneForAdTest(model: PhotoViewModel, photo: Photo) {
        compose.runOnIdle { model.openMonth(photo.month) }
        compose.waitUntil(20_000) { !model.state.value.busy && model.state.value.remaining.firstOrNull()?.uri == photo.uri }
        compose.runOnIdle { model.decide(photo, Decision.REMOVE) }
        compose.waitUntil(20_000) { !model.state.value.busy && model.state.value.marked.any { it.uri == photo.uri } }
        compose.runOnIdle { model.navigate("trash"); model.requestDelete(setOf(photo.uri)) }
        if (model.platform.deleteMode == DeleteMode.BATCH) {
            val approve = UiDevice.getInstance(instrumentation).wait(androidx.test.uiautomator.Until.findObject(
                androidx.test.uiautomator.By.res("android", "button1")), 10_000)
            Assert.assertNotNull("Android deletion consent", approve)
            approve.click()
        }
    }
    @Test fun reviewUndoPersistCancelAndDelete() {
        val beforeDeletion = MediaStorePhotoSource(context).query()
        val expectedFreed = beforeDeletion.first().bytes
        val snoozed = InstrumentationRegistry.getArguments().getString("ratingSnoozed") == "true"
        if (snoozed) {
            context.getSharedPreferences("rating_invitation", 0).edit()
                .putLong("rate_remind_after", System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000).commit()
        }
        waitFor("Photo Cleaner"); capture("01-months")
        compose.onNodeWithText("Start sorting").assertIsDisplayed().performClick()
        waitFor("1 / 2"); waitForPhoto("Sep 20, 2026"); capture("02-review")
        compose.onNodeWithContentDescription("Gesture guide").performClick()
        waitFor("One photo. One swipe."); capture("08-gesture-guide")
        compose.onNodeWithText("Got it").performClick()
        choose("Remove")
        waitFor("2 / 2")
        compose.onNodeWithContentDescription("Undo last swipe", useUnmergedTree = true).performClick()
        waitFor("1 / 2")
        compose.onNodeWithTag("review-photo").performTouchInput {
            swipe(center, center + androidx.compose.ui.geometry.Offset(0f, 25f), 250)
        }
        waitFor("1 / 2")
        compose.onNodeWithTag("review-photo").performTouchInput { swipeLeft() }
        waitFor("1 / 2")
        // Exercise actual gestures as well as accessible button alternatives.
        waitForPhoto("Sep 20, 2026")
        compose.onNodeWithTag("review-photo").performTouchInput { swipeDown() }
        waitFor("2 / 2")
        compose.activityRule.scenario.recreate()
        waitFor("2 / 2")
        compose.onNodeWithText("Keep").assertHasClickAction()
        waitForPhoto("Sep 15, 2026"); capture("10-landscape-fit")
        compose.onNodeWithTag("review-photo").performTouchInput { swipeUp() }
        waitFor("Review removals"); capture("24-completion-removals")
        compose.onNodeWithText("Review removals").performClick()
        waitFor("Select all"); compose.onNodeWithText("Select all").performClick(); waitFor("Delete · 1"); capture("03-to-delete")
        compose.onNodeWithText("Delete · 1").performClick()
        compose.onNodeWithText("Cancel").performClick()
        Assert.assertEquals(3, accessibleFixtureCount())
        compose.onNodeWithText("Delete · 1").performClick()
        when (PhotoPlatform.current().deleteMode) {
            DeleteMode.LEGACY -> {
                waitFor("Delete photos")
                capture("04-app-confirmation")
                compose.onNodeWithText("Delete photos").performClick()
            }
            DeleteMode.SCOPED -> {
                // These fixtures belong to the app. Android 10 foreign-owner consent/retry
                // is separately covered by coordinator tests; this exercises the device backend.
                compose.onNodeWithText("Delete photos").performClick()
            }
            DeleteMode.BATCH -> {
                compose.onNodeWithText("Delete photos").performClick()
                val device = UiDevice.getInstance(instrumentation)
                val cancel = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.res("android", "button2")), 10_000)
                Assert.assertNotNull("Android must present its deletion confirmation", cancel)
                capture("04-system-confirmation"); cancel.click()
                waitFor("Deletion canceled. Your photos were kept.")
                compose.onNodeWithText("Cleanup complete").assertDoesNotExist()
                Assert.assertEquals(3, accessibleFixtureCount()); capture("07-canceled")
                val dismissAction = SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)
                if (compose.onAllNodes(dismissAction).fetchSemanticsNodes().isNotEmpty()) compose.onNode(dismissAction).performSemanticsAction(SemanticsActions.Dismiss) { it() }
                compose.waitUntil(20_000) {
                    compose.onAllNodesWithText("Deletion canceled. Your photos were kept.").fetchSemanticsNodes().isEmpty() &&
                        compose.onAllNodes(hasText("Delete · 1") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Delete · 1").performClick(); waitFor("Delete photos"); compose.onNodeWithText("Delete photos").performClick()
                val approve = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.res("android", "button1")), 10_000)
                Assert.assertNotNull(approve); approve.click()
            }
        }
        waitFor("Cleanup complete")
        compose.onNodeWithText("Not now").assertDoesNotExist()
        waitFor("1 photo deleted")
        compose.onNodeWithText(PhotoDetailFormat.size(expectedFreed)).assertExists()
        if (snoozed) compose.onNodeWithTag("rating-invitation").assertDoesNotExist() else waitFor("Rate Photo Cleaner")
        capture(if (snoozed) "28-deletion-snoozed" else "27-deletion-result")
        Assert.assertEquals(2, accessibleFixtureCount())
        compose.activityRule.scenario.recreate()
        waitFor("Cleanup complete")
        if (snoozed) compose.onNodeWithTag("rating-invitation").assertDoesNotExist()
        else {
            compose.onNodeWithTag("rating-invitation").assertExists()
            verifyRatingCooldownOnDeletionResult()
        }
        compose.onNodeWithText("Done").performClick()
        waitFor("To remove")
        compose.onNodeWithText("Nothing to remove").assertExists()
        compose.onNodeWithText("Months").performClick()
        waitFor("Photo Cleaner")
        waitFor("✓ Completed")
        compose.onNodeWithText("Start sorting").assertDoesNotExist()
        val model = androidx.lifecycle.ViewModelProvider(compose.activity)[PhotoViewModel::class.java]
        compose.runOnIdle { model.refresh() }
        compose.waitUntil(20_000) { model.state.value.photos.size == 2 && model.state.value.currentGroup?.completed == true }
        Assert.assertEquals(beforeDeletion.drop(1).map { it.uri }, model.state.value.photos.map { it.uri })
        val persisted = ReviewDatabase(context)
        try {
            Assert.assertEquals(beforeDeletion.drop(1), persisted.snapshot(beforeDeletion).photos)
            Assert.assertEquals(Decision.KEEP, persisted.read()[beforeDeletion[1].uri]?.decision)
        } finally { persisted.close() }
        compose.onNodeWithText("September").performClick()
        waitFor("1 / 1"); waitForPhoto("Sep 15, 2026")
        compose.onNodeWithContentDescription("Photo from Sep 20, 2026").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to months").performClick()
        waitFor("Photo Cleaner")
        compose.onNodeWithContentDescription("Settings").performClick()
        waitFor("Rate Photo Cleaner"); capture("06-settings")
        compose.onNodeWithText("Privacy policy").performScrollTo().performClick()
        waitFor("Your photos. Your device."); capture("25-privacy")
        compose.activityRule.scenario.recreate()
        waitFor("Your photos. Your device.")
        compose.onNodeWithContentDescription("Back to settings").performClick()
        waitFor("At your own pace.")
        compose.onNodeWithText("Privacy policy").performScrollTo().performClick()
        waitFor("Your photos. Your device.")
        androidx.test.espresso.Espresso.pressBack()
        waitFor("At your own pace.")
    }
    @Test fun completedMonthsCanBeRevisitedAndTrashReturnsToSamePhoto() {
        waitFor("Photo Cleaner")
        compose.onNodeWithText("September").performClick()
        waitFor("1 / 2")
        compose.onNodeWithContentDescription("Fill screen").assertDoesNotExist()
        compose.onNodeWithContentDescription("View entire photo").assertDoesNotExist()
        choose("Keep")
        waitFor("2 / 2")
        choose("Keep")
        waitFor("Month, sorted."); capture("09-month-sorted")
        compose.onNodeWithText("Browse again").performClick()
        waitFor("1 / 2")
        compose.onNodeWithText("Kept").assertDoesNotExist()
        choose("Remove")
        waitFor("2 / 2")
        compose.onNodeWithContentDescription("Review removals").performClick()
        waitFor("To remove")
        compose.onNodeWithText("Continue sorting").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to sorting").performClick()
        waitFor("2 / 2"); waitForPhoto("Sep 15, 2026")
        compose.onNodeWithContentDescription("Review removals").performClick()
        waitFor("To remove")
        // Android back follows the same origin as the visible arrow, even after recreation.
        compose.activityRule.scenario.recreate()
        waitFor("To remove")
        androidx.test.espresso.Espresso.pressBack()
        waitFor("2 / 2")
        compose.onNodeWithContentDescription("Undo last swipe").performClick()
        waitFor("1 / 2"); compose.onNodeWithText("Kept").assertDoesNotExist()
        choose("Remove")
        waitFor("2 / 2")
        compose.onNodeWithContentDescription("Review removals").performClick()
        waitFor("Select all"); compose.onNodeWithText("Select all").performClick()
        compose.onNodeWithText("Keep selected").performClick()
        waitFor("Nothing to remove")
        compose.onNodeWithText("Continue sorting").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to sorting").performClick()
        waitFor("2 / 2")
        Assert.assertEquals(3, accessibleFixtureCount())
        compose.onNodeWithContentDescription("Back to months").performClick()
        waitFor("Photo Cleaner")
        compose.onNodeWithText("✓ Completed").assertExists()
        compose.onNodeWithText("Browse again").assertDoesNotExist()
        compose.onNodeWithText("Start sorting").assertDoesNotExist()
        compose.onNodeWithText("Continue sorting").assertDoesNotExist()
        capture("18-completed-home")
        compose.onNodeWithText("September").performClick()
        waitFor("1 / 2")
        compose.onNodeWithContentDescription("Back to months").performClick()
        waitFor("Photo Cleaner")
        compose.onNodeWithContentDescription("Settings").performClick()
        waitFor("Reset review progress")
        compose.onNodeWithText("Reset review progress").performScrollTo().performClick()
        waitFor("Reset review progress?")
        compose.onNodeWithText("Reset progress").performClick()
        waitFor("Start sorting") // Reset writes on IO; the home title is already visible before it finishes.
        compose.onNodeWithText("✓ Completed").assertDoesNotExist()
        Assert.assertEquals(3, accessibleFixtureCount())
    }
    @Test fun cleanupSoundPreferenceSurvivesRecreationAndNewViewModel() {
        waitFor("Photo Cleaner")
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithContentDescription("Cleanup sounds").assertIsOn().performClick()
        compose.onNodeWithContentDescription("Cleanup sounds").assertIsOff()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("Cleanup sounds").assertIsOff()
        val store = androidx.lifecycle.ViewModelStore()
        compose.runOnIdle {
            val restarted = PhotoViewModel(context.applicationContext as android.app.Application, androidx.lifecycle.SavedStateHandle())
            store.put("sound-preferences", restarted)
            Assert.assertFalse(restarted.state.value.cleanupSoundsEnabled)
            store.clear()
        }
        compose.onNodeWithContentDescription("Cleanup sounds").performClick()
        compose.onNodeWithContentDescription("Cleanup sounds").assertIsOn()
        capture("30-cleanup-sound-settings")
    }
    @Test fun photoDetailsFollowThePhotoAndCanBeCollapsed() {
        val photos = MediaStorePhotoSource(context).query()
        waitFor("Photo Cleaner"); compose.onNodeWithText("September").performClick()
        waitFor("1 / 2")
        compose.onNodeWithText("Full photo").assertDoesNotExist()
        compose.onNodeWithText("Sep 20").assertDoesNotExist()
        val stage = compose.onNodeWithTag("review-photo").fetchSemanticsNode().boundsInRoot
        val details = compose.onNodeWithContentDescription("Show photo details").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue(details.center.x < stage.center.x && details.center.y > stage.center.y)
        Assert.assertTrue(details.bottom <= stage.bottom)
        val size = compose.onNodeWithTag("photo-file-size").assertTextEquals(PhotoDetailFormat.size(photos[0].bytes))
            .fetchSemanticsNode().boundsInRoot
        Assert.assertTrue(size.center.x > stage.center.x && size.right <= stage.right)
        Assert.assertEquals(details.center.y, size.center.y, 1f)
        capture("20-rounded-photo-and-size")
        compose.onNodeWithContentDescription("Show photo details").performClick()
        waitFor(photos[0].name)
        compose.onNodeWithText("Folder").assertExists()
        capture("11-photo-details")
        compose.onNodeWithTag("photo-details").performTouchInput { click(center) }
        compose.onNodeWithTag("photo-details").assertExists()
        compose.onNodeWithTag("review-photo").performTouchInput { click(percentOffset(.5f, .2f)) }
        compose.onNodeWithTag("photo-details").assertDoesNotExist()
        compose.onNodeWithText("1 / 2").assertExists()
        compose.onNodeWithContentDescription("Show photo details").performClick()
        waitFor(photos[0].name)
        compose.onNodeWithTag("photo-details").performTouchInput { swipeUp() }
        compose.onNodeWithText("1 / 2").assertExists()
        compose.onNodeWithTag("photo-details").performScrollToNode(hasText("Photo location"))
        if (Build.VERSION.SDK_INT >= 29 && context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            compose.onNodeWithText("Photo location access required").assertExists()
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.ACCESS_MEDIA_LOCATION").use {
                java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
            }
            compose.activityRule.scenario.recreate()
            waitFor("1 / 2")
            compose.onNodeWithContentDescription("Show photo details").performClick()
            waitFor(photos[0].name)
            compose.onNodeWithTag("photo-details").performScrollToNode(hasText("Photo location"))
        }
        waitFor("37.77490, -122.41940")
        compose.onNodeWithText("37.77490, -122.41940").performScrollTo().assertIsDisplayed()
        capture("12-photo-location")
        androidx.test.espresso.Espresso.pressBack()
        waitFor("1 / 2"); compose.onNodeWithTag("photo-details").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show photo details").performClick()
        compose.onNodeWithText("Loading details…").assertDoesNotExist()
        waitFor(photos[0].name)
        choose("Keep")
        waitFor("2 / 2"); waitFor(photos[1].name)
        compose.onNodeWithTag("photo-file-size").assertTextEquals(PhotoDetailFormat.size(photos[1].bytes))
        compose.onNodeWithText(photos[0].name).assertDoesNotExist()
        compose.onNodeWithTag("photo-details").performScrollToNode(hasText("Photo location"))
        compose.onNodeWithText("Not recorded in this photo").assertExists()
        compose.onNodeWithContentDescription("Hide photo details").performClick()
        compose.onNodeWithTag("photo-details").assertDoesNotExist()
        Assert.assertEquals(3, accessibleFixtureCount())
    }
    @Suppress("DEPRECATION")
    @Test fun shareIntentGrantsReadAccessToOnlyTheCurrentPhoto() = kotlinx.coroutines.runBlocking<Unit> {
        val photo = MediaStorePhotoSource(context).query().first()
        val chooser = PhotoShare.prepare(context, photo)
        Assert.assertEquals(systemShareChooserAction(), chooser.action)
        val send = chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
        Assert.assertEquals(android.content.Intent.ACTION_SEND, send.action)
        Assert.assertEquals("image/jpeg", send.type)
        Assert.assertEquals(photo.uri, send.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM).toString())
        for (intent in listOf(chooser, send)) {
            Assert.assertEquals(1, intent.clipData!!.itemCount)
            Assert.assertEquals(photo.uri, intent.clipData!!.getItemAt(0).uri.toString())
            Assert.assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            Assert.assertEquals(0, intent.flags and (android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
        }
        Assert.assertTrue(runCatching { PhotoShare.prepare(context, photo.copy(modified = photo.modified + 1)) }.exceptionOrNull() is java.io.FileNotFoundException)
        Assert.assertEquals(3, accessibleFixtureCount())
    }
    @Suppress("DEPRECATION")
    @Test fun shareCurrentPhotoAndCancelWithoutChangingSorting() {
        waitFor("Photo Cleaner")
        compose.onNodeWithText("September").performClick()
        waitFor("1 / 2"); waitForPhoto("Sep 20, 2026")
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("Share photo") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val toolbar = compose.onNodeWithTag("review-toolbar").fetchSemanticsNode().boundsInRoot
        val share = compose.onNodeWithContentDescription("Share photo").fetchSemanticsNode().boundsInRoot
        Assert.assertEquals(toolbar.center.x, share.center.x, 1f)
        Assert.assertEquals(toolbar.center.y, share.center.y, 1f)
        capture("31-review-share")
        val model = androidx.lifecycle.ViewModelProvider(compose.activity)[PhotoViewModel::class.java]
        val before = model.state.value
        compose.onNodeWithContentDescription("Share photo").performClick()
        val device = UiDevice.getInstance(instrumentation)
        compose.waitUntil(15_000) { device.currentPackageName?.let { it != context.packageName } == true }
        capture("32-share-sheet")
        device.pressBack()
        waitFor("1 / 2"); waitForPhoto("Sep 20, 2026")
        Assert.assertEquals(before.session, model.state.value.session)
        Assert.assertEquals(before.records, model.state.value.records)
        Assert.assertEquals(3, accessibleFixtureCount())
        choose("Keep"); waitFor("2 / 2"); waitForPhoto("Sep 15, 2026")
        val next = model.state.value.remaining.first()
        val records = model.state.value.records
        val captured = java.util.concurrent.atomic.AtomicReference<android.content.Intent>()
        val chooserAction = systemShareChooserAction()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action != chooserAction) return null
                captured.set(intent)
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("Share photo") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Share photo").performClick()
            compose.waitUntil(15_000) { captured.get() != null }
            val send = captured.get().getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
            Assert.assertEquals(next.uri, send.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM).toString())
            Assert.assertEquals(records, model.state.value.records)
            waitFor("2 / 2")
        } finally { instrumentation.removeMonitor(monitor) }
    }
    // Some devices customize the action returned by Android's chooser factory.
    private fun systemShareChooserAction(): String? = android.content.Intent.createChooser(
        android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/jpeg"), "Share photo"
    ).action

    @Test fun pinchInspectsTheSamePhotoAndReleaseRestoresSorting() {
        waitFor("Photo Cleaner"); capture("01-months")
        compose.onNodeWithText("Start sorting").performClick()
        waitFor("1 / 2"); waitForPhoto("Sep 20, 2026")
        compose.onNodeWithContentDescription("Fill screen").assertDoesNotExist()
        capture("15-before-pinch")
        compose.onNodeWithTag("review-photo").performTouchInput {
            down(0, percentOffset(.35f, .5f)); down(1, percentOffset(.55f, .5f))
            repeat(12) { step ->
                val t = (step + 1) / 12f
                updatePointerTo(0, percentOffset(.35f - .2f * t, .5f - .02f * t))
                updatePointerTo(1, percentOffset(.55f + .2f * t, .5f + .02f * t)); move(16)
            }
        }
        compose.onNodeWithText("Keep").assertIsNotEnabled()
        capture("16-pinch-detail")
        compose.onNodeWithTag("review-photo").performTouchInput {
            up(1); moveTo(0, percentOffset(.2f, .82f)); up(0)
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Keep") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        waitFor("1 / 2"); capture("17-pinch-reset")
        val records = ReviewDatabase(context)
        try { Assert.assertTrue(records.read().isEmpty()) } finally { records.close() }
        compose.onNodeWithTag("review-photo").performTouchInput { swipeUp() }
        waitFor("2 / 2"); Assert.assertEquals(3, accessibleFixtureCount())
    }
    @Test fun keepingAllPhotosDoesNotInviteRating() {
        waitFor("Photo Cleaner")
        compose.onNodeWithText("Start sorting").performClick()
        waitFor("1 / 2"); choose("Keep")
        waitFor("2 / 2"); choose("Keep")
        waitFor("Month, sorted.")
        compose.onNodeWithTag("rating-invitation").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        waitFor("Month, sorted.")
        compose.onNodeWithText("Rate Photo Cleaner").assertDoesNotExist()
    }
    @Test fun sortingCompletionOnlyOffersReviewAndNavigation() {
        waitFor("Photo Cleaner")
        compose.onNodeWithText("Start sorting").performClick()
        waitFor("1 / 2"); choose("Remove")
        waitFor("2 / 2"); choose("Keep")
        waitFor("Month, sorted.")
        compose.onNodeWithText("Already rated").assertDoesNotExist()
        compose.onNodeWithText("Rate Photo Cleaner").assertDoesNotExist()
        compose.onNodeWithTag("rating-invitation").assertDoesNotExist()
        capture("26-month-no-rating")
        compose.onNodeWithText("Review removals").performClick()
        waitFor("To remove")
        Assert.assertEquals(3, accessibleFixtureCount())
    }
    private fun verifyRatingCooldownOnDeletionResult() {
        var opened: String? = null
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action != android.content.Intent.ACTION_VIEW) return null
                opened = intent.dataString
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithText("Rate Photo Cleaner").performScrollTo().performClick()
            compose.waitForIdle()
            Assert.assertEquals("market://details?id=${context.packageName}", opened)
            compose.onNodeWithTag("rating-invitation").assertDoesNotExist()
            compose.activityRule.scenario.recreate()
            waitFor("Cleanup complete")
            compose.onNodeWithTag("rating-invitation").assertDoesNotExist()
            context.getSharedPreferences("rating_invitation", 0).edit()
                .putLong("rate_remind_after", System.currentTimeMillis() - 1).commit()
            compose.activityRule.scenario.recreate()
            waitFor("Rate Photo Cleaner")
            compose.onNodeWithText("Not now").assertDoesNotExist()
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun batchValidationMatchesSingleReadsAndRejectsStaleSignatures() {
        val source = MediaStorePhotoSource(context)
        val photos = source.query()
        val items = photos.map { DeleteItem(it.uri, it.signature, it.bytes) }
        Assert.assertEquals(items.map { it.uri }.toSet(), source.currentUris(items))
        items.forEach { Assert.assertTrue(source.isCurrent(it.uri, it.signature)) }
        val changed = items.map { it.copy(signature = "stale") }
        Assert.assertTrue(source.currentUris(changed).isEmpty())
        changed.forEach { Assert.assertFalse(source.isCurrent(it.uri, it.signature)) }
    }
    @Test fun batchJournalWriteRollsBackOnMissingItem() {
        val items = MediaStorePhotoSource(context).query().map { DeleteItem(it.uri, it.signature, it.bytes) }
        val journal = DeletionJournal(context)
        try {
            journal.create(DeleteSession("atomic-receipts", DeleteMode.BATCH, items))
            val result = runCatching { journal.saveItems(listOf(
                items.first().copy(status = DeleteStatus.DELETED),
                items.last().copy(uri = "missing", status = DeleteStatus.DELETED))) }
            Assert.assertTrue(result.isFailure)
            Assert.assertTrue(journal.load()!!.items.all { it.status == DeleteStatus.PENDING })
            journal.saveItems(items.map { it.copy(status = DeleteStatus.DELETED) })
            Assert.assertTrue(journal.load()!!.items.all { it.status == DeleteStatus.DELETED })
        } finally { journal.clear(); journal.close() }
    }
    @Test fun interruptedDeletionReconcilesOnStartupWithoutLosingProgress() {
        waitFor("Photo Cleaner")
        val photos = MediaStorePhotoSource(context).query()
        val reviews = ReviewDatabase(context)
        val journal = DeletionJournal(context)
        try {
            reviews.writeAll(photos.associate { it.uri to ReviewRecord(it.signature, Decision.REMOVE, false) })
            // Simulate a crash after one confirmed delete and before processing the rest.
            // The provider deliberately still returns all fixture rows.
            journal.create(DeleteSession("interrupted-sync", DeleteMode.LEGACY,
                photos.mapIndexed { index, photo -> DeleteItem(photo.uri, photo.signature, photo.bytes,
                    if (index == 0) DeleteStatus.DELETED else DeleteStatus.IN_FLIGHT) }))
            val store = androidx.lifecycle.ViewModelStore()
            lateinit var restarted: PhotoViewModel
            compose.runOnIdle {
                restarted = PhotoViewModel(context.applicationContext as android.app.Application, androidx.lifecycle.SavedStateHandle())
                store.put("restarted", restarted)
            }
            try {
                compose.waitUntil(20_000) { !restarted.state.value.loading }
                val state = restarted.state.value
                Assert.assertEquals(photos.drop(1), state.photos)
                Assert.assertEquals(1, state.deletedCount)
                Assert.assertEquals(2, state.deleteFailedCount)
                Assert.assertTrue(state.groups.all { it.completed })
                Assert.assertEquals(photos.drop(1), state.marked)
                Assert.assertNull(journal.load())
                Assert.assertEquals(3, accessibleFixtureCount()) // Recovery never retries deletion.
                kotlinx.coroutines.runBlocking {
                    val snapshot = PhotoRepository(context).load()
                    Assert.assertEquals(state.photos, snapshot.photos)
                    Assert.assertEquals(state.records, snapshot.records)
                }
            } finally { compose.runOnIdle { store.clear() } }
        } finally { journal.clear(); journal.close(); reviews.close() }
    }
    private fun prepareFastSwipes(): List<Photo> {
        val photos = MediaStorePhotoSource(context).query()
        waitFor("Photo Cleaner")
        compose.onNodeWithText("Start sorting").performClick()
        waitFor("1 / 2")
        compose.waitUntil(20_000) {
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
                .fetchSemanticsNodes().isEmpty()
        }
        compose.mainClock.autoAdvance = false
        return photos
    }
    private fun startFastKeep() {
        compose.onNodeWithTag("review-photo").performTouchInput {
            swipe(center, percentOffset(.5f, .15f), durationMillis = 80)
        }
        compose.mainClock.advanceTimeBy(32) // Leave the 230 ms exit animation unfinished.
    }
    private fun decisions(): Map<String, ReviewRecord> {
        val db = ReviewDatabase(context)
        return try { db.read() } finally { db.close() }
    }
    @Test fun rapidOppositeSwipeSurvivesPhotoPromotionUnderTheFinger() {
        val photos = prepareFastSwipes()
        try {
            startFastKeep()
            Assert.assertTrue(decisions().isEmpty())
            compose.onNodeWithTag("review-photo").performTouchInput {
                down(center); moveTo(percentOffset(.5f, .8f), 64)
            }
            compose.waitUntil(10_000) { decisions().size == 1 }
            compose.mainClock.advanceTimeBy(32)
            // The second DOWN was on the outgoing card, but UP is after next-photo promotion.
            compose.onNodeWithTag("review-photo").performTouchInput { up() }
        } finally { compose.mainClock.autoAdvance = true }
        waitFor("Month, sorted.")
        val records = decisions()
        Assert.assertEquals(2, records.size)
        Assert.assertEquals(Decision.KEEP, records[photos[0].uri]?.decision)
        Assert.assertEquals(Decision.REMOVE, records[photos[1].uri]?.decision)
        Assert.assertEquals(3, accessibleFixtureCount())
    }
    @Test fun twoCompleteFlicksBeforeTheFirstAnimationEndsKeepTheirOwnTargets() {
        val photos = prepareFastSwipes()
        try {
            compose.onNodeWithTag("review-photo").performTouchInput {
                swipe(center, percentOffset(.5f, .15f), durationMillis = 80)
                swipe(center, percentOffset(.5f, .85f), durationMillis = 80)
            }
        } finally { compose.mainClock.autoAdvance = true }
        waitFor("Month, sorted.")
        Assert.assertEquals(2, decisions().size)
        Assert.assertEquals(Decision.KEEP, decisions()[photos[0].uri]?.decision)
        Assert.assertEquals(Decision.REMOVE, decisions()[photos[1].uri]?.decision)
    }
    @Test fun canceledNextStrokeDoesNotCreateADeferredRemoval() {
        val photos = prepareFastSwipes()
        try {
            startFastKeep()
            compose.onNodeWithTag("review-photo").performTouchInput {
                down(center); moveTo(percentOffset(.5f, .8f), 64); cancel()
            }
        } finally { compose.mainClock.autoAdvance = true }
        waitFor("2 / 2")
        compose.waitForIdle()
        Assert.assertEquals(1, decisions().size)
        Assert.assertEquals(Decision.KEEP, decisions()[photos[0].uri]?.decision)
        Assert.assertNull(decisions()[photos[1].uri])
    }
    @Test fun failedSaveDiscardsTheNextStrokeInsteadOfApplyingItToAnotherPhoto() {
        val photos = prepareFastSwipes()
        val db = ReviewDatabase(context)
        try {
            db.writableDatabase.execSQL("CREATE TRIGGER reject_test_review BEFORE INSERT ON reviews BEGIN SELECT RAISE(ABORT, 'test write failure'); END")
            startFastKeep()
            compose.onNodeWithTag("review-photo").performTouchInput {
                down(center); moveTo(percentOffset(.5f, .8f), 64); up()
            }
            compose.mainClock.autoAdvance = true
            waitFor("Could not save this change. Please try again.")
            compose.waitForIdle()
            Assert.assertTrue(decisions().isEmpty())
            compose.onNodeWithText("1 / 2").assertExists()
        } finally {
            compose.mainClock.autoAdvance = true
            db.writableDatabase.execSQL("DROP TRIGGER IF EXISTS reject_test_review")
            db.close()
        }
        compose.onNodeWithTag("review-photo").performTouchInput { swipeUp() }
        waitFor("2 / 2")
        Assert.assertEquals(Decision.KEEP, decisions()[photos[0].uri]?.decision)
        Assert.assertNull(decisions()[photos[1].uri])
    }
    private fun accessibleFixtureCount(): Int = testPhotos.count()
}
