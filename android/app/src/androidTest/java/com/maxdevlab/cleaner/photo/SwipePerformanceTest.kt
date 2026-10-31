package com.maxdevlab.cleaner.photo

import android.Manifest
import android.app.KeyguardManager
import android.os.*
import android.view.FrameMetrics
import android.view.Window
import coil.imageLoader
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

/** Real input and real vsync; no Compose test-clock acceleration. Only isolated fixture media. */
@RunWith(AndroidJUnit4::class)
class SwipePerformanceTest {
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
        val emulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")
        check(emulator || InstrumentationRegistry.getArguments().getString("allowPhysicalDevice") == "true")
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val db = ReviewDatabase(context)
        try { db.clear() } finally { db.close() }
        photos.create(count = 24, large = true)
    }
    @After fun cleanup() { photos.cleanRegisteredPhotos() }

    @Test fun continuousSwipeAndButtons() {
        val frames = ArrayList<Long>()
        val uiWork = ArrayList<Long>()
        val gpuWork = ArrayList<Long>()
        val callbackThread = HandlerThread("Frame measurement").apply { start() }
        val handler = Handler(callbackThread.looper)
        var listenerAttached = false
        var lostReports = 0
        var refreshRate = 60f
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION)); lostReports += dropped
                uiWork.add(listOf(FrameMetrics.INPUT_HANDLING_DURATION, FrameMetrics.ANIMATION_DURATION,
                    FrameMetrics.LAYOUT_MEASURE_DURATION, FrameMetrics.DRAW_DURATION).sumOf { metrics.getMetric(it).coerceAtLeast(0L) })
                if (Build.VERSION.SDK_INT >= 31) gpuWork.add(metrics.getMetric(FrameMetrics.GPU_DURATION).coerceAtLeast(0L))
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                checkNotNull(device.wait(Until.findObject(By.text("September")), 30_000)).click()
                check(device.wait(Until.hasObject(By.text("1 / 24")), 15_000))
                // Allow the first visible pair to decode; subsequent transitions are measured.
                SystemClock.sleep(1_500)
                scenario.onActivity {
                    @Suppress("DEPRECATION")
                    refreshRate = it.windowManager.defaultDisplay.refreshRate
                    it.window.addOnFrameMetricsAvailableListener(listener, handler)
                    listenerAttached = true
                }
                val start = SystemClock.elapsedRealtime()
                for (index in 1..18) {
                    if (index % 3 == 0) checkNotNull(device.findObject(By.text("Keep"))).click()
                    else {
                        val direction = if (index % 2 == 0) -1 else 1
                        val center = device.displayHeight / 2
                        device.swipe(device.displayWidth / 2, center, device.displayWidth / 2,
                            center + direction * device.displayHeight / 4, 35)
                    }
                    check(device.wait(Until.hasObject(By.text("${index + 1} / 24")), 15_000)) { "Swipe $index did not advance" }
                }
                val elapsed = SystemClock.elapsedRealtime() - start
                scenario.onActivity {
                    if (listenerAttached) it.window.removeOnFrameMetricsAvailableListener(listener)
                    listenerAttached = false
                }
                callbackThread.quitSafely(); callbackThread.join()
                val sorted = frames.sorted()
                check(sorted.size >= 100) { "Too few measured frames: ${sorted.size}" }
                fun percentile(p: Double) = sorted[((sorted.size - 1) * p).toInt()] / 1_000_000.0
                fun p95(values: List<Long>): Double? = values.sorted().takeIf { it.isNotEmpty() }
                    ?.let { it[((it.size - 1) * .95).toInt()] / 1_000_000.0 }
                val budget = 1_000_000_000.0 / refreshRate
                val cache = context.imageLoader.memoryCache
                val previews = cache?.keys.orEmpty().filter { ":preview:" in it.key }.mapNotNull { cache?.get(it)?.bitmap }
                if (InstrumentationRegistry.getArguments().getString("verifyPreviewBudget") == "true") {
                    Assert.assertTrue("Review previews should be cached", previews.isNotEmpty())
                    Assert.assertTrue("Decoded previews must fit the pixel budget", previews.all { maxOf(it.width, it.height) <= 2048 })
                    Assert.assertTrue("Cache must respect the memory cap", cache!!.size <= cache.maxSize && cache.maxSize <= 48 * 1024 * 1024)
                }
                val result = JSONObject().apply {
                    put("cacheMb", (cache?.size ?: 0) / 1048576.0)
                    put("cacheLimitMb", (cache?.maxSize ?: 0) / 1048576.0)
                    put("cachedReviewPreviews", previews.size)
                    put("largestPreviewEdge", previews.maxOfOrNull { maxOf(it.width, it.height) } ?: 0)
                    put("device", Build.MODEL); put("api", Build.VERSION.SDK_INT)
                    put("debuggable", (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                    put("refreshHz", refreshRate); put("photos", 24); put("photoPixels", "4032x3024 / 3024x4032")
                    put("transitions", 18); put("elapsedMs", elapsed); put("frames", sorted.size)
                    put("p50Ms", percentile(.50)); put("p90Ms", percentile(.90)); put("p95Ms", percentile(.95)); put("p99Ms", percentile(.99))
                    put("p95UiWorkMs", p95(uiWork)); put("p95GpuWorkMs", p95(gpuWork))
                    put("overRefreshBudgetPercent", sorted.count { it > budget } * 100.0 / sorted.size)
                    put("over32Ms", sorted.count { it > 32_000_000L }); put("droppedMetricReports", lostReports)
                    put("javaHeapMb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576.0)
                    put("nativeHeapMb", Debug.getNativeHeapAllocatedSize() / 1048576.0)
                }
                File(context.getExternalFilesDir(null), "swipe-performance.json").writeText(result.toString(2))
                println("SWIPE_PERFORMANCE: $result")
                Assert.assertEquals(24, photos.count())
            } finally {
                scenario.onActivity {
                    if (listenerAttached) it.window.removeOnFrameMetricsAvailableListener(listener)
                    listenerAttached = false
                }
                callbackThread.quitSafely()
            }
        }
    }
}
