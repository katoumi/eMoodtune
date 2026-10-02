package com.example.moodsync

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

object HistoryStorage {

    private fun getDao(context: Context) = AppDatabase.getDatabase(context).historyDao()

    fun saveHistoryItem(context: Context, item: HistoryItem) {
        runBlocking {
            getDao(context).insert(item)
        }
    }

    fun getHistory(context: Context): List<HistoryItem> {
        return runBlocking {
            getDao(context).getAllHistory().first()
        }
    }

    fun clearHistory(context: Context) {
        runBlocking {
            getDao(context).deleteAll()
        }
    }

    fun markSessionPlayed(context: Context, sessionId: String) {
        runBlocking {
            val item = getDao(context).getBySessionId(sessionId)
            if (item != null) {
                getDao(context).update(item.copy(wasPlayed = true, wasSkipped = false))
            }
        }
    }

    fun markSessionReplayed(context: Context, sessionId: String) {
        runBlocking {
            val item = getDao(context).getBySessionId(sessionId)
            if (item != null) {
                getDao(context).update(item.copy(wasReplayed = true, wasPlayed = true, wasSkipped = false))
            }
        }
    }

    fun markSessionSkipped(context: Context, sessionId: String) {
        runBlocking {
            val item = getDao(context).getBySessionId(sessionId)
            if (item != null) {
                // If it was skipped, it wasn't really "played" in the feedback sense
                getDao(context).update(item.copy(wasSkipped = true, wasPlayed = false))
            }
        }
    }

    fun updateSessionMoodAfter(context: Context, sessionId: String, moodAfter: String) {
        runBlocking {
            val item = getDao(context).getBySessionId(sessionId)
            if (item != null) {
                getDao(context).update(item.copy(moodAfter = moodAfter))
            }
        }
    }
}
