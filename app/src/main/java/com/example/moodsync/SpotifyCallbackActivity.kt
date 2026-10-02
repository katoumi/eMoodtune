package com.example.moodsync

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class SpotifyCallbackActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = intent?.data
        val code = data?.getQueryParameter("code")
        val error = data?.getQueryParameter("error")

        if (!error.isNullOrBlank()) {
            Toast.makeText(this, "Spotify login cancelled: $error", Toast.LENGTH_LONG).show()
            goHome()
            return
        }

        if (code.isNullOrBlank()) {
            Toast.makeText(this, "Spotify login failed: missing code", Toast.LENGTH_LONG).show()
            goHome()
            return
        }

        val codeVerifier = SpotifyAuthManager.getStoredCodeVerifier(this)
        if (codeVerifier.isNullOrBlank()) {
            Toast.makeText(this, "Spotify login failed: missing verifier", Toast.LENGTH_LONG).show()
            goHome()
            return
        }

        thread {
            try {
                val tokenResult = SpotifyAuthManager.exchangeCodeForToken(
                    code = code,
                    codeVerifier = codeVerifier
                )

                SpotifyTokenStorage.saveTokens(
                    context = this,
                    accessToken = tokenResult.accessToken,
                    refreshToken = tokenResult.refreshToken,
                    expiresInSeconds = tokenResult.expiresIn
                )

                SpotifyAuthManager.clearStoredCodeVerifier(this)

                runOnUiThread {
                    Toast.makeText(this, "Spotify connected", Toast.LENGTH_SHORT).show()
                    goHome()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Spotify login failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    goHome()
                }
            }
        }
    }

    private fun goHome() {
        startActivity(
            Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}