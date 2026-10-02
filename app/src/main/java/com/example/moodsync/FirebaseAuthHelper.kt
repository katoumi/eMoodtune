package com.example.moodsync

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

object FirebaseAuthHelper {

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    fun currentUserId(): String? = auth.currentUser?.uid

    fun isLoggedIn(): Boolean = auth.currentUser != null

    fun registerUser(
        username: String,
        email: String,
        password: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (username.isBlank() || email.isBlank() || password.isBlank()) {
            onError("Please complete all fields")
            return
        }

        auth.createUserWithEmailAndPassword(email.trim(), password)
            .addOnSuccessListener { result ->
                val uid = result.user?.uid
                if (uid == null) {
                    onError("Account created but user ID was not found")
                    return@addOnSuccessListener
                }

                val userData: MutableMap<String, Any> = hashMapOf(
                    "uid" to uid,
                    "username" to username.trim(),
                    "email" to email.trim(),
                    "birth_day" to "Day",
                    "birth_month" to "Month",
                    "birth_year" to "",
                    "country" to "Philippines",
                    "createdAt" to System.currentTimeMillis()
                )

                db.collection("users")
                    .document(uid)
                    .set(userData)
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener { onError(it.message ?: "Failed to save account info") }
            }
            .addOnFailureListener {
                onError(it.message ?: "Registration failed")
            }
    }

    fun loginUser(
        email: String,
        password: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (email.isBlank() || password.isBlank()) {
            onError("Please enter email and password")
            return
        }

        auth.signInWithEmailAndPassword(email.trim(), password)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onError(it.message ?: "Login failed") }
    }

    fun sendPasswordReset(
        email: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (email.isBlank()) {
            onError("Enter your email first")
            return
        }

        auth.sendPasswordResetEmail(email.trim())
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onError(it.message ?: "Failed to send reset email") }
    }

    fun loginWithGoogle(
        credential: com.google.firebase.auth.AuthCredential,
        photoUrl: String?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        auth.signInWithCredential(credential)
            .addOnSuccessListener { result ->
                val user = result.user
                if (user != null) {
                    checkAndCreateUserDoc(user.uid, user.displayName, user.email, photoUrl, onSuccess, onError)
                } else {
                    onError("Google login failed")
                }
            }
            .addOnFailureListener {
                onError(it.message ?: "Google login failed")
            }
    }

    private fun checkAndCreateUserDoc(
        uid: String,
        name: String?,
        email: String?,
        photoUrl: String?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    onSuccess()
                } else {
                    val userData: MutableMap<String, Any> = hashMapOf(
                        "uid" to uid,
                        "username" to (name ?: "User"),
                        "email" to (email ?: ""),
                        "profileImageUrl" to (photoUrl ?: ""),
                        "birth_day" to "Day",
                        "birth_month" to "Month",
                        "birth_year" to "",
                        "country" to "Philippines",
                        "createdAt" to System.currentTimeMillis()
                    )
                    db.collection("users").document(uid).set(userData)
                        .addOnSuccessListener { onSuccess() }
                        .addOnFailureListener { onError(it.message ?: "Failed to initialize user data") }
                }
            }
            .addOnFailureListener {
                onError(it.message ?: "Database check failed")
            }
    }

    fun logout() {
        auth.signOut()
    }
}
