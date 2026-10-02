package com.example.moodsync

import android.content.Context

object SpotifyTokenStorage {
    private const val PREF_NAME = "spotify_auth_pref"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at"

    fun saveTokens(
        context: Context,
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Long
    ) {
        val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000L)

        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .apply()
    }

    fun getAccessToken(context: Context): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACCESS_TOKEN, null)
    }

    fun getRefreshToken(context: Context): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_REFRESH_TOKEN, null)
    }

    fun getExpiresAt(context: Context): Long {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_EXPIRES_AT, 0L)
    }

    fun isLoggedIn(context: Context): Boolean {
        return !getAccessToken(context).isNullOrBlank()
    }

    fun isAccessTokenValid(context: Context): Boolean {
        val token = getAccessToken(context)
        val expiresAt = getExpiresAt(context)
        // Add a 60-second buffer to prevent race conditions where token expires
        // during the network request.
        val bufferMs = 60 * 1000L
        return !token.isNullOrBlank() && (System.currentTimeMillis() + bufferMs) < expiresAt
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}