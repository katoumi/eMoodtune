package com.example.moodsync

import com.google.firebase.firestore.FirebaseFirestore

object FirebaseUserFeedbackHelper {

    private val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    fun sendFeedback(
        message: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val uid = FirebaseAuthHelper.currentUserId()

        if (uid == null) {
            onError("User not logged in")
            return
        }

        if (message.isBlank()) {
            onError("Please enter your feedback first")
            return
        }

        val feedbackData: MutableMap<String, Any> = hashMapOf(
            "uid" to uid,
            "message" to message.trim(),
            "createdAt" to System.currentTimeMillis(),
            "status" to "new"
        )

        db.collection("users")
            .document(uid)
            .collection("app_feedback")
            .add(feedbackData)
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener {
                onError(it.message ?: "Failed to send feedback")
            }
    }
}