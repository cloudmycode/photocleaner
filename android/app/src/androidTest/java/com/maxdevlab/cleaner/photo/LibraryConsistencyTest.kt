package com.maxdevlab.cleaner.photo

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maxdevlab.cleaner.photo.data.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Uses a dedicated database and synthetic metadata; never touches device photos. */
@RunWith(AndroidJUnit4::class)
class LibraryConsistencyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "library-consistency-test.db"
    private lateinit var db: ReviewDatabase
    private val photos = (1..4).map { Photo("photo:$it", "$it.jpg", 1000L - it, 100, 100, 100, 1) }
    private val records = photos.associate { it.uri to ReviewRecord(it.signature, Decision.REMOVE, false) }
    private fun report(statuses: List<DeleteStatus>) = DeleteReport("test-report", photos.zip(statuses).map { (photo, status) ->
        DeleteItem(photo.uri, photo.signature, photo.bytes, status)
    }, false)

    @Before fun setUp() {
        check(BuildConfig.ISOLATED_TEST_BUILD && context.packageName.endsWith(".devicetest"))
        context.deleteDatabase(name)
        db = ReviewDatabase(context, name)
        db.writeAll(records)
    }
    @After fun tearDown() { db.close(); context.deleteDatabase(name) }

    @Test fun successfulReceiptsSurviveRestartAndResetButFailuresRemainReviewed() {
        val result = report(listOf(DeleteStatus.DELETED, DeleteStatus.FAILED, DeleteStatus.CANCELED, DeleteStatus.IN_FLIGHT))
        db.applyDeleteReport(result)
        db.close(); db = ReviewDatabase(context, name)
        db.applyDeleteReport(result) // Crash before journal acknowledgement must be harmless.
        val snapshot = db.snapshot(photos) // Deliberately return the stale provider list.
        assertEquals(photos.drop(1), snapshot.photos)
        assertEquals(records - photos[0].uri, snapshot.records)
        assertTrue(ReviewRules.groups(snapshot.photos, snapshot.records).single().completed)
        db.clear()
        assertEquals(photos.drop(1), db.snapshot(photos).photos)
        assertTrue(db.read().isEmpty())
    }

    @Test fun oldReceiptDoesNotRemoveNewReviewAtReusedUri() {
        val replacement = photos[0].copy(modified = 2)
        val record = ReviewRecord(replacement.signature, Decision.KEEP, false)
        db.write(replacement.uri, record)
        db.applyDeleteReport(report(listOf(DeleteStatus.DELETED)))
        assertEquals(listOf(replacement), db.snapshot(listOf(replacement)).photos)
        assertEquals(record, db.read()[replacement.uri])
    }

    @Test fun receiptAndReviewUpdatesRollBackTogether() {
        db.writableDatabase.execSQL("CREATE TRIGGER reject_receipt BEFORE INSERT ON deleted_photos WHEN NEW.uri='photo:2' BEGIN SELECT RAISE(ABORT, 'simulated disk failure'); END")
        assertTrue(runCatching { db.applyDeleteReport(report(listOf(DeleteStatus.DELETED, DeleteStatus.DELETED))) }.isFailure)
        assertEquals(LibrarySnapshot(photos, records), db.snapshot(photos))
    }

    @Test fun versionOneUpgradePreservesEveryReview() {
        db.close(); context.deleteDatabase(name)
        val old = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        try {
            old.execSQL("CREATE TABLE reviews (uri TEXT PRIMARY KEY NOT NULL, signature TEXT NOT NULL, decision TEXT, favorite INTEGER NOT NULL DEFAULT 0)")
            records.forEach { (uri, record) -> old.execSQL("INSERT INTO reviews VALUES (?, ?, ?, ?)", arrayOf<Any>(uri, record.signature, record.decision!!.name, 0)) }
            old.version = 1
        } finally { old.close() }
        db = ReviewDatabase(context, name)
        assertEquals(LibrarySnapshot(photos, records), db.snapshot(photos))
        db.applyDeleteReport(report(listOf(DeleteStatus.DELETED)))
        assertEquals(photos.drop(1), db.snapshot(photos).photos)
    }
}
