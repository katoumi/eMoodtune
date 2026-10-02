package com.example.moodsync

import android.content.Context
import android.util.Log

object SpotifySessionManager {

    private const val TAG = "TOKEN_DEBUG"

    @Synchronized
    fun getValidAccessToken(context: Context): String? {
        val token = SpotifyTokenStorage.getAccessToken(context)
        val expiresAt = SpotifyTokenStorage.getExpiresAt(context)
        val isValid = SpotifyTokenStorage.isAccessTokenValid(context)
        val now = System.currentTimeMillis()

        Log.d(TAG, "=== TOKEN CHECK ===")
        Log.d(TAG, "token=${if (token.isNullOrBlank()) "NULL/EMPTY" else token.take(20) + "..."}")
        Log.d(TAG, "expiresAt=$expiresAt now=$now diff=${expiresAt - now}ms isValid=$isValid")

        if (isValid) {
            Log.d(TAG, "Token is valid, returning existing token")
            return token
        }

        Log.d(TAG, "Token invalid or expired, attempting refresh...")

        val refreshToken = SpotifyTokenStorage.getRefreshToken(context)

        if (refreshToken.isNullOrBlank()) {
            Log.e(TAG, "No refresh token found — user needs to log in again")
            return null
        }

        Log.d(TAG, "refresh token found=${refreshToken.take(10)}...")

        var retryCount = 0
        val maxRetries = 3
        var lastException: Exception? = null

        while (retryCount < maxRetries) {
            try {
                val refreshed = SpotifyAuthManager.refreshAccessToken(refreshToken)

                Log.d(TAG, "Refresh SUCCESS — new token=${refreshed.accessToken.take(20)}... expiresIn=${refreshed.expiresIn}s")

                SpotifyTokenStorage.saveTokens(
                    context = context,
                    accessToken = refreshed.accessToken,
                    refreshToken = refreshed.refreshToken ?: refreshToken,
                    expiresInSeconds = refreshed.expiresIn
                )

                return refreshed.accessToken
            } catch (e: Exception) {
                lastException = e
                retryCount++
                Log.w(TAG, "Refresh attempt $retryCount failed: ${e.message}")
                if (retryCount < maxRetries) {
                    Thread.sleep(1000L * retryCount) // Exponential-ish backoff
                }
            }
        }

        Log.e(TAG, "Refresh FAILED after $maxRetries attempts: ${lastException?.message}", lastException)
        return null
    }
}