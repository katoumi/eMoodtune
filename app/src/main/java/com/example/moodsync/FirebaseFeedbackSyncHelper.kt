package com.example.moodsync

import com.google.firebase.firestore.FirebaseFirestore

object FirebaseFeedbackSyncHelper {

    private val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    fun syncFeedbackItem(
        item: HistoryItem,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val uid = FirebaseAuthHelper.currentUserId()

        if (uid == null) {
            onError?.invoke("User not logged in")
            return
        }

        val docId = item.sessionId.ifBlank {
            "${item.epochMillis}_${item.songTitle}_${item.artist}"
                .replace("/", "_")
                .replace("\\", "_")
                .replace(" ", "_")
        }

        val feedbackData: MutableMap<String, Any> = hashMapOf(
            "sessionId" to item.sessionId,
            "emotion" to item.emotion,
            "songTitle" to item.songTitle,
            "artist" to item.artist,
            "moodBefore" to item.moodBefore,
            "moodAfter" to item.moodAfter,
            "recommendedSongTitle" to item.recommendedSongTitle,
            "recommendedArtist" to item.recommendedArtist,
            "recommendationSource" to item.recommendationSource,
            "wasPlayed" to item.wasPlayed,
            "wasSkipped" to item.wasSkipped,
            "wasReplayed" to item.wasReplayed,
            "epochMillis" to item.epochMillis,
            "dayOfWeek" to item.dayOfWeek,
            "timeOfDay" to item.timeOfDay,
            "syncedAt" to System.currentTimeMillis()
        )

        db.collection("users")
            .document(uid)
            .collection("feedback")
            .document(docId)
            .set(feedbackData)
            .addOnSuccessListener { onSuccess?.invoke() }
            .addOnFailureListener { onError?.invoke(it.message ?: "Feedback sync failed") }
    }
}