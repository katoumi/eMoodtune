package com.example.moodsync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object SpotifyAuthManager {

    private const val PREF_NAME = "spotify_auth_runtime"
    private const val KEY_CODE_VERIFIER = "code_verifier"

    fun startLogin(context: Context) {
        val codeVerifier = SpotifyPkceUtil.generateCodeVerifier()
        val codeChallenge = SpotifyPkceUtil.generateCodeChallenge(codeVerifier)

        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CODE_VERIFIER, codeVerifier)
            .apply()

        val authUri = Uri.Builder()
            .scheme("https")
            .authority("accounts.spotify.com")
            .path("authorize")
            .appendQueryParameter("client_id", SpotifyConfig.CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", SpotifyConfig.REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("scope", SpotifyConfig.SCOPES)
            .build()

        val customTabsIntent = CustomTabsIntent.Builder().build()
        customTabsIntent.launchUrl(context, authUri)
    }

    fun getStoredCodeVerifier(context: Context): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CODE_VERIFIER, null)
    }

    fun clearStoredCodeVerifier(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CODE_VERIFIER)
            .apply()
    }

    fun exchangeCodeForToken(
        code: String,
        codeVerifier: String
    ): SpotifyTokenResult {
        val url = URL("https://accounts.spotify.com/api/token")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }

        val form = buildString {
            append("client_id=")
            append(encode(SpotifyConfig.CLIENT_ID))
            append("&grant_type=authorization_code")
            append("&code=")
            append(encode(code))
            append("&redirect_uri=")
            append(encode(SpotifyConfig.REDIRECT_URI))
            append("&code_verifier=")
            append(encode(codeVerifier))
        }

        OutputStreamWriter(connection.outputStream).use { writer ->
            writer.write(form)
            writer.flush()
        }

        val responseCode = connection.responseCode
        val responseText = if (responseCode in 200..299) {
            connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } else {
            connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                ?: "Unknown Spotify token error"
        }

        if (responseCode !in 200..299) {
            throw Exception("Spotify token exchange failed: $responseCode $responseText")
        }

        val json = JSONObject(responseText)
        return SpotifyTokenResult(
            accessToken = json.getString("access_token"),
            tokenType = json.optString("token_type", "Bearer"),
            expiresIn = json.optLong("expires_in", 3600L),
            refreshToken = if (json.has("refresh_token")) json.optString("refresh_token") else null
        )
    }

    fun refreshAccessToken(
        refreshToken: String
    ): SpotifyTokenResult {
        val url = URL("https://accounts.spotify.com/api/token")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }

        val form = buildString {
            append("client_id=")
            append(encode(SpotifyConfig.CLIENT_ID))
            append("&grant_type=refresh_token")
            append("&refresh_token=")
            append(encode(refreshToken))
        }

        OutputStreamWriter(connection.outputStream).use { writer ->
            writer.write(form)
            writer.flush()
        }

        val responseCode = connection.responseCode
        val responseText = if (responseCode in 200..299) {
            connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } else {
            connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                ?: "Unknown Spotify refresh error"
        }

        if (responseCode !in 200..299) {
            throw Exception("Spotify token refresh failed: $responseCode $responseText")
        }

        val json = JSONObject(responseText)
        return SpotifyTokenResult(
            accessToken = json.getString("access_token"),
            tokenType = json.optString("token_type", "Bearer"),
            expiresIn = json.optLong("expires_in", 3600L),
            refreshToken = if (json.has("refresh_token")) json.optString("refresh_token") else null
        )
    }

    fun openSpotifyTrack(context: Context, spotifyUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(spotifyUrl))
        context.startActivity(intent)
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    data class SpotifyTokenResult(
        val accessToken: String,
        val tokenType: String,
        val expiresIn: Long,
        val refreshToken: String?
    )
}