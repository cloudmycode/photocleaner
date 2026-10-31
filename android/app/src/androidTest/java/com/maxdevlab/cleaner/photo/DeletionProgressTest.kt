package com.maxdevlab.cleaner.photo

import android.Manifest
import android.content.IntentSender
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.maxdevlab.cleaner.photo.data.*
import com.maxdevlab.cleaner.photo.platform.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Timings include only generated, allowlisted photos in the independent test app. */
@RunWith(AndroidJUnit4::class)
class DeletionProgressTest {
    @get:Rule val permissions = GrantPermissionRule.grant(*when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    })

    @Test fun legacyBatchTimingAndDurableProgress() = runBlocking<Unit> {
        Assume.assumeTrue(Build.VERSION.SDK_INT < 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(BuildConfig.ISOLATED_TEST_BUILD && context.packageName.endsWith(".devicetest"))
        val emulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")
        check(emulator || InstrumentationRegistry.getArguments().getString("allowPhysicalDevice") == "true")
        val fixture = TestPhotoFixture(context)
        val journal = DeletionJournal(context)
        var validationNanos = 0L; var deletionNanos = 0L; var journalNanos = 0L
        fun <T> timed(record: (Long) -> Unit, action: () -> T): T {
            val start = System.nanoTime()
            try { return action() } finally { record(System.nanoTime() - start) }
        }
        try {
            fixture.create(count = 40)
            val photos = MediaStorePhotoSource(context).query()
            assertEquals(40, photos.size)
            journal.clear()
            val backend = androidDeletionBackend(context)
            val measuredBackend = object : DeletionBackend<IntentSender> by backend {
                override fun isCurrent(item: DeleteItem) = timed({ validationNanos += it }) { backend.isCurrent(item) }
                override fun deleteOne(item: DeleteItem) = timed({ deletionNanos += it }) { backend.deleteOne(item) }
            }
            val measuredStore = object : DeletionStore by journal {
                override fun create(session: DeleteSession) = timed({ journalNanos += it }) { journal.create(session) }
                override fun saveHeader(session: DeleteSession) = timed({ journalNanos += it }) { journal.saveHeader(session) }
                override fun saveItem(item: DeleteItem) = timed({ journalNanos += it }) { journal.saveItem(item) }
            }
            val updates = mutableListOf<DeleteProgress>()
            val start = System.nanoTime()
            val result = PhotoDeleteCoordinator(measuredBackend, measuredStore, updates::add).start(photos) as DeleteStep.Complete
            val elapsed = (System.nanoTime() - start) / 1_000_000
            assertEquals(40, result.report.deleted.size)
            assertEquals((0..40).toList(), updates.map { it.processed }.distinct())
            assertEquals(photos.sumOf { it.bytes }, updates.last().freedBytes)
            assertEquals(40, journal.load()!!.items.count { it.status == DeleteStatus.DELETED })
            assertEquals(0, fixture.count())
            Log.i("DeletionTiming", "photos=40 totalMs=$elapsed validationMs=${validationNanos / 1_000_000} systemDeleteMs=${deletionNanos / 1_000_000} journalMs=${journalNanos / 1_000_000} progressUpdates=${updates.size}")
        } finally { journal.clear(); journal.close(); fixture.cleanRegisteredPhotos() }
    }
}
