package com.maxdevlab.cleaner.photo

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.maxdevlab.cleaner.photo.ads.*
import com.maxdevlab.cleaner.photo.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CleanupAdDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun isolatedOnly() {
        check(BuildConfig.ISOLATED_TEST_BUILD && context.packageName.endsWith(".devicetest"))
    }
    @Test fun cleanupCountResetsForANewSessionAndDuplicateReportsDoNotAdvanceIt() {
        isolatedOnly()
        fun report(id: String) = DeleteReport(id, listOf(DeleteItem("fixture", "sig", 1, DeleteStatus.DELETED)), false)
        val session = CleanupAdSession()
        assertFalse(session.record(report("first")))
        assertFalse(session.record(report("first")))
        assertFalse(session.record(report("second")))
        assertTrue(session.record(report("third")))
        assertFalse(session.record(report("third")))
        assertTrue(session.record(report("fourth")))
        assertEquals(3, session.count)
        val nextSession = CleanupAdSession()
        assertEquals(0, nextSession.count)
        assertFalse(nextSession.record(report("first")))
        assertFalse(nextSession.record(report("second")))
        assertTrue(nextSession.record(report("third")))
    }
    /** Optional network smoke test. A no-fill/network failure is reported, never replaced with a fake ad. */
    @Test fun officialGoogleTestInterstitialLoadsShowsAndCloses() {
        isolatedOnly()
        assertTrue(BuildConfig.AD_TEST_MODE)
        val app = context.applicationContext as PhotoCleanerApplication
        val ads = AdMobCleanupAds(context)
        app.cleanupAds = ads
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
            var ready = false
            while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { ready = ads.hasReadyAd }
                if (!ready) Thread.sleep(250)
            }
            assertTrue("Official test ad unavailable: check network/AdMob response; no fake fallback", ready)
            val finished = CountDownLatch(1)
            scenario.onActivity { ads.showIfReady(it) { finished.countDown() } }
            val device = UiDevice.getInstance(instrumentation)
            Thread.sleep(1500)
            val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            assertTrue(device.takeScreenshot(File(dir, "35-official-admob-test-ad.png")))
            val close = device.wait(Until.findObject(By.descContains("Close")), 20_000)
                ?: device.findObject(By.text("Close"))
            if (close != null) close.click() else device.pressBack()
            assertTrue("Closing test ad must finish presentation", finished.await(20, TimeUnit.SECONDS))
        }
    }
}
