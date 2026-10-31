package com.maxdevlab.cleaner.photo

import android.Manifest
import android.os.*
import android.view.FrameMetrics
import android.view.MotionEvent.PointerCoords
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.*
import com.maxdevlab.cleaner.photo.data.ReviewDatabase
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

/** Real two-finger input on generated 12 MP images, with the system frame clock. */
@RunWith(AndroidJUnit4::class)
class PinchPerformanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val photos = TestPhotoFixture(context)
    @get:Rule val permissions = GrantPermissionRule.grant(*when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    })
    @Before fun prepare() {
        check(BuildConfig.ISOLATED_TEST_BUILD && context.packageName.endsWith(".devicetest"))
        check(Build.MODEL.contains("sdk_gphone") || InstrumentationRegistry.getArguments().getString("allowPhysicalDevice") == "true")
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
        val db = ReviewDatabase(context)
        try { db.clear() } finally { db.close() }
        photos.create(large = true)
    }
    @After fun cleanup() { photos.cleanRegisteredPhotos() }

    @Test fun repeatedPinchAndReturn() {
        val frames = ArrayList<Long>()
        val ui = ArrayList<Long>()
        val thread = HandlerThread("Pinch frames").apply { start() }
        var dropped = 0
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
                ui.add(listOf(FrameMetrics.INPUT_HANDLING_DURATION, FrameMetrics.ANIMATION_DURATION,
                    FrameMetrics.LAYOUT_MEASURE_DURATION, FrameMetrics.DRAW_DURATION).sumOf { metrics.getMetric(it).coerceAtLeast(0) })
                dropped += lost
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var attached = false
            try {
                check(device.wait(Until.hasObject(By.text("Start sorting")), 30_000))
                device.wait(Until.findObject(By.pkg("com.android.systemui").text("Got it")), 2_000)?.click()
                device.findObject(By.text("Start sorting")).click()
                check(device.wait(Until.hasObject(By.text("1 / 2")), 15_000))
                SystemClock.sleep(2_000)
                scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)); attached = true }
                val rect = device.findObject(UiSelector().description("Photo from Sep 20, 2026")).bounds
                val left = Array(65) { i -> PointerCoords().apply {
                    x = rect.centerX() - rect.width() * (.1f + .22f * i / 64); y = rect.centerY().toFloat(); pressure = 1f; size = 1f
                } }
                val right = Array(65) { i -> PointerCoords().apply {
                    x = rect.centerX() + rect.width() * (.1f + .22f * i / 64); y = rect.centerY().toFloat(); pressure = 1f; size = 1f
                } }
                repeat(8) {
                    check(device.findObject(UiSelector().description("Photo from Sep 20, 2026")).performMultiPointerGesture(left, right))
                    SystemClock.sleep(700)
                }
                scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener); attached = false }
                thread.quitSafely(); thread.join()
                check(frames.size > 100)
                fun percentile(values: List<Long>, p: Double) = values.sorted().let { it[((it.size - 1) * p).toInt()] / 1_000_000.0 }
                val result = JSONObject().apply {
                    put("device", Build.MODEL); put("api", Build.VERSION.SDK_INT); put("pinches", 8); put("frames", frames.size)
                    put("p50Ms", percentile(frames, .5)); put("p95Ms", percentile(frames, .95)); put("p99Ms", percentile(frames, .99))
                    put("p95UiMs", percentile(ui, .95)); put("over32Ms", frames.count { it > 32_000_000 }); put("lostReports", dropped)
                }
                File(context.getExternalFilesDir(null), "pinch-performance.json").writeText(result.toString(2))
                println("PINCH_PERFORMANCE: $result")
                check(device.hasObject(By.text("1 / 2")))
                val db = ReviewDatabase(context)
                try { Assert.assertTrue(db.read().isEmpty()) } finally { db.close() }
                Assert.assertEquals(3, photos.count())
            } finally {
                if (attached) scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
                thread.quitSafely()
            }
        }
    }
}
