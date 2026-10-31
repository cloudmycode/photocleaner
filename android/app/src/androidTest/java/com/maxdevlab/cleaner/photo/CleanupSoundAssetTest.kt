package com.maxdevlab.cleaner.photo

import android.media.AudioAttributes
import android.media.SoundPool
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CleanupSoundAssetTest {
    @Test fun completionSoundDecodesOnDeviceWithoutExtraPermissions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val statuses = ConcurrentHashMap<Int, Int>()
        val latch = CountDownLatch(1)
        lateinit var pool: SoundPool
        instrumentation.runOnMainSync {
            pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
            pool.setOnLoadCompleteListener { _, id, status -> statuses[id] = status; latch.countDown() }
            assertTrue(pool.load(context, R.raw.cleanup_complete, 1) > 0)
        }
        try {
            assertTrue("Completion sound must decode", latch.await(10, TimeUnit.SECONDS))
            assertEquals(1, statuses.size)
            assertTrue(statuses.values.all { it == 0 })
        } finally { instrumentation.runOnMainSync { pool.release() } }
    }
}
