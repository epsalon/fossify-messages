package org.fossify.messages.helpers.spam

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Separate SQLite file (not the Room messages database, which uses destructive migrations).
 * Holds the paid-call cache, the decision log, and the STOP reply cooldown. Everything stays on the device.
 */
class SpamDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "spam_filter.db", null, 1) {

    data class Decision(
        val created: Long,
        val sender: String,
        val body: String,
        val directive: String,
        val localSpam: Double?,
        val llmSpam: Double?,
        val llmWanted: Double?,
        val verdict: String,
        val reason: String,
        val stopSent: Boolean
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE llm_cache (key TEXT PRIMARY KEY, spam REAL NOT NULL, wanted REAL NOT NULL, created INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE decisions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, created INTEGER NOT NULL, sender TEXT NOT NULL, " +
                "body TEXT NOT NULL, directive TEXT NOT NULL, local_spam REAL, llm_spam REAL, llm_wanted REAL, " +
                "verdict TEXT NOT NULL, reason TEXT NOT NULL, stop_sent INTEGER NOT NULL)"
        )
        db.execSQL("CREATE TABLE stop_replies (sender TEXT PRIMARY KEY, replied_at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun cachedAnswer(key: String): Pair<Double, Double>? = readableDatabase.rawQuery(
        "SELECT spam, wanted FROM llm_cache WHERE key = ?", arrayOf(key)
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getDouble(0) to cursor.getDouble(1) else null
    }

    fun storeAnswer(key: String, spam: Double, wanted: Double) {
        writableDatabase.insertWithOnConflict(
            "llm_cache",
            null,
            ContentValues().apply {
                put("key", key)
                put("spam", spam)
                put("wanted", wanted)
                put("created", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun lastStopReply(sender: String): Long? = readableDatabase.rawQuery(
        "SELECT replied_at FROM stop_replies WHERE sender = ?", arrayOf(sender)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    fun recordStopReply(sender: String, repliedAt: Long) {
        writableDatabase.insertWithOnConflict(
            "stop_replies",
            null,
            ContentValues().apply {
                put("sender", sender)
                put("replied_at", repliedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun logDecision(decision: Decision) {
        writableDatabase.insert("decisions", null, ContentValues().apply {
            put("created", decision.created)
            put("sender", decision.sender)
            put("body", decision.body)
            put("directive", decision.directive)
            decision.localSpam?.let { put("local_spam", it) }
            decision.llmSpam?.let { put("llm_spam", it) }
            decision.llmWanted?.let { put("llm_wanted", it) }
            put("verdict", decision.verdict)
            put("reason", decision.reason)
            put("stop_sent", if (decision.stopSent) 1 else 0)
        })
    }

    companion object {
        @Volatile
        private var instance: SpamDatabase? = null

        fun get(context: Context): SpamDatabase = instance ?: synchronized(this) {
            instance ?: SpamDatabase(context).also { instance = it }
        }
    }
}
