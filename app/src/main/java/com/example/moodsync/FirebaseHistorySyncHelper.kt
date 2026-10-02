package com.example.moodsync

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore

object FirebaseHistorySyncHelper {

    private val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    fun syncLocalHistoryToCloud(
        context: Context,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val uid = FirebaseAuthHelper.currentUserId()

        if (uid == null) {
            onError?.invoke("User not logged in")
            return
        }

        val history = HistoryStorage.getHistory(context)

        if (history.isEmpty()) {
            onSuccess?.invoke()
            return
        }

        val batch = db.batch()
        val historyRef = db.collection("users")
            .document(uid)
            .collection("history")

        history.forEach { item ->
            val docId = item.sessionId.ifBlank {
                "${item.epochMillis}_${item.songTitle}_${item.artist}"
                    .replace("/", "_")
                    .replace("\\", "_")
            }

            val data: MutableMap<String, Any> = hashMapOf(
                "emotion" to item.emotion,
                "songTitle" to item.songTitle,
                "artist" to item.artist,
                "duration" to item.duration,
                "timestamp" to item.timestamp,
                "epochMillis" to item.epochMillis,
                "dayOfWeek" to item.dayOfWeek,
                "timeOfDay" to item.timeOfDay,
                "source" to item.source,
                "moodBefore" to item.moodBefore,
                "moodAfter" to item.moodAfter,
                "recommendedSongTitle" to item.recommendedSongTitle,
                "recommendedArtist" to item.recommendedArtist,
                "recommendationSource" to item.recommendationSource,
                "wasPlayed" to item.wasPlayed,
                "wasSkipped" to item.wasSkipped,
                "wasReplayed" to item.wasReplayed,
                "sessionId" to item.sessionId,
                "syncedAt" to System.currentTimeMillis()
            )

            batch.set(historyRef.document(docId), data)
        }

        batch.commit()
            .addOnSuccessListener { onSuccess?.invoke() }
            .addOnFailureListener { onError?.invoke(it.message ?: "History sync failed") }
    }
}