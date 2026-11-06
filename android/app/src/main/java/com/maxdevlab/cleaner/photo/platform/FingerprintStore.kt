package com.maxdevlab.cleaner.photo.platform

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.maxdevlab.cleaner.photo.data.PhotoFingerprint

internal data class CachedFingerprint(val uri: String, val signature: String, val fingerprint: PhotoFingerprint)
internal interface FingerprintStore {
    fun read(): Map<String, CachedFingerprint>
    fun write(entries: List<CachedFingerprint>)
    fun close() = Unit
}

/** Keep version 1 and its fingerprints; never discard a user's completed scan during an update. */
internal class SqliteFingerprintStore(context: Context, databaseName: String = "cleanup_fingerprints.db") : FingerprintStore {
    private val helper = object : SQLiteOpenHelper(context, databaseName, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE fingerprint (uri TEXT PRIMARY KEY, signature TEXT NOT NULL, duplicate TEXT NOT NULL, difference INTEGER NOT NULL, variance INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    override fun close() = helper.close()

    override fun read(): Map<String, CachedFingerprint> {
        val db = helper.readableDatabase
        return buildMap {
            db.query("fingerprint", arrayOf("uri", "signature", "duplicate", "difference", "variance"), null, null, null, null, null).use { c ->
                while (c.moveToNext()) {
                    val entry = CachedFingerprint(c.getString(0), c.getString(1), PhotoFingerprint(c.getString(2), c.getLong(3), c.getInt(4)))
                    put(entry.uri, entry)
                }
            }
        }
    }

    override fun write(entries: List<CachedFingerprint>) {
        if (entries.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.compileStatement("INSERT OR REPLACE INTO fingerprint (uri, signature, duplicate, difference, variance) VALUES (?, ?, ?, ?, ?)").use { statement ->
                entries.forEach { entry ->
                    statement.bindString(1, entry.uri)
                    statement.bindString(2, entry.signature)
                    statement.bindString(3, entry.fingerprint.duplicate)
                    statement.bindLong(4, entry.fingerprint.difference)
                    statement.bindLong(5, entry.fingerprint.variance.toLong())
                    statement.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
