package com.maxdevlab.cleaner.photo.platform

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.maxdevlab.cleaner.photo.data.*

/** Per-item durable receipts avoid rewriting a large JSON queue after every photo. */
class DeletionJournal(context: Context) : SQLiteOpenHelper(context, "deletions.db", null, 1), DeletionStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE session (id TEXT PRIMARY KEY, mode TEXT NOT NULL, awaiting TEXT NOT NULL, interrupted INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE items (uri TEXT PRIMARY KEY, ordinal INTEGER NOT NULL, signature TEXT NOT NULL, bytes INTEGER NOT NULL, status TEXT NOT NULL, consent INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    override fun load(): DeleteSession? {
        readableDatabase.query("session", null, null, null, null, null, null).use { c ->
            if (!c.moveToFirst()) return null
            val items = buildList {
                readableDatabase.query("items", null, null, null, null, null, "ordinal ASC").use { row ->
                    while (row.moveToNext()) add(DeleteItem(row.getString(0), row.getString(2), row.getLong(3), DeleteStatus.valueOf(row.getString(4)), row.getInt(5) == 1))
                }
            }
            return DeleteSession(c.getString(0), DeleteMode.valueOf(c.getString(1)), items, DeleteAwaiting.valueOf(c.getString(2)), c.getInt(3) == 1)
        }
    }
    override fun create(session: DeleteSession) {
        val db = writableDatabase; db.beginTransaction()
        try {
            clear(); saveHeader(session)
            session.items.forEachIndexed { index, item ->
                db.insertOrThrow("items", null, itemValues(item).apply { put("uri", item.uri); put("ordinal", index); put("signature", item.signature); put("bytes", item.bytes) })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    override fun saveHeader(session: DeleteSession) {
        check(writableDatabase.insertWithOnConflict("session", null, ContentValues().apply {
            put("id", session.id); put("mode", session.mode.name); put("awaiting", session.awaiting.name); put("interrupted", if (session.interrupted) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }
    private fun itemValues(item: DeleteItem) = ContentValues().apply { put("status", item.status.name); put("consent", if (item.consentUsed) 1 else 0) }
    override fun saveItem(item: DeleteItem) { check(writableDatabase.update("items", itemValues(item), "uri=?", arrayOf(item.uri)) == 1) }
    override fun saveItems(items: List<DeleteItem>) {
        if (items.isEmpty()) return
        val db = writableDatabase; db.beginTransaction()
        try { items.forEach(::saveItem); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    override fun clear() {
        val db = writableDatabase; db.beginTransaction()
        try { db.delete("items", null, null); db.delete("session", null, null); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
}
