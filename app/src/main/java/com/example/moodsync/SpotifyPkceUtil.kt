package com.example.moodsync

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object SpotifyPkceUtil {

    fun generateCodeVerifier(length: Int = 64): String {
        val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val random = SecureRandom()
        return buildString {
            repeat(length) {
                append(allowed[random.nextInt(allowed.length)])
            }
        }
    }

    fun generateCodeChallenge(codeVerifier: String): String {
        val bytes = codeVerifier.toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }
}