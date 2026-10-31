package com.maxdevlab.cleaner.photo.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Only review metadata is stored. No image bytes, GPS, faces, or OCR. Calls run on IO. */
class ReviewDatabase(context: Context, name: String = "reviews.db") : SQLiteOpenHelper(context, name, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE reviews (uri TEXT PRIMARY KEY NOT NULL, signature TEXT NOT NULL, decision TEXT, favorite INTEGER NOT NULL DEFAULT 0)")
        createDeletedPhotos(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDeletedPhotos(db)
    }
    private fun createDeletedPhotos(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE deleted_photos (uri TEXT NOT NULL, signature TEXT NOT NULL, PRIMARY KEY (uri, signature))")
    }
    /** Receipts survive refresh/restart, even when the provider briefly returns deleted rows. */
    fun snapshot(photos: List<Photo>): LibrarySnapshot {
        val db = readableDatabase
        db.beginTransaction()
        try {
            val deleted = buildSet {
                db.query("deleted_photos", arrayOf("uri", "signature"), null, null, null, null, null).use { c ->
                    while (c.moveToNext()) add(PhotoIdentity(c.getString(0), c.getString(1)))
                }
            }
            val snapshot = LibrarySnapshot(photos, read()).excluding(deleted)
            db.setTransactionSuccessful()
            return snapshot
        } finally { db.endTransaction() }
    }
    fun read(): Map<String, ReviewRecord> = buildMap {
        readableDatabase.query("reviews", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) put(c.getString(0), ReviewRecord(c.getString(1), c.getString(2)?.let(Decision::valueOf), c.getInt(3) == 1))
        }
    }
    fun write(uri: String, record: ReviewRecord?) {
        if (record == null) { writableDatabase.delete("reviews", "uri=?", arrayOf(uri)); return }
        val values = ContentValues().apply {
            put("uri", uri); put("signature", record.signature); put("decision", record.decision?.name); put("favorite", if (record.favorite) 1 else 0)
        }
        check(writableDatabase.insertWithOnConflict("reviews", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Could not persist review" }
    }
    fun writeAll(records: Map<String, ReviewRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try { records.forEach { (uri, record) -> write(uri, record) }; db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    /** Commit receipts and matching review removal together before acknowledging the journal. */
    fun applyDeleteReport(report: DeleteReport) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            report.deleted.forEach { item ->
                val values = ContentValues().apply { put("uri", item.uri); put("signature", item.signature) }
                check(db.insertWithOnConflict("deleted_photos", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                db.delete("reviews", "uri=? AND signature=?", arrayOf(item.uri, item.signature))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    // Resetting review progress must not resurrect confirmed deletions.
    fun clear() { writableDatabase.delete("reviews", null, null) }
}
