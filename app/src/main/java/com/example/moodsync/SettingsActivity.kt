package com.example.moodsync

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions

class SettingsActivity : AppCompatActivity() {

    private lateinit var imgProfileTop: ImageView

    private lateinit var cardAccountInfo: LinearLayout
    private lateinit var cardFaceCalibration: LinearLayout
    private lateinit var tvCalibrationStatus: TextView
    private lateinit var cardFaq: LinearLayout
    private lateinit var cardFeedback: LinearLayout
    private lateinit var cardLogout: LinearLayout

    private lateinit var navHome: LinearLayout
    private lateinit var navScan: LinearLayout
    private lateinit var navProfile: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        bindViews()
        setupCards()
        setupBottomNav()
    }

    override fun onResume() {
        super.onResume()
        ProfileImageLoader.load(imgProfileTop)
        updateCalibrationStatus()
    }

    private fun bindViews() {
        imgProfileTop = findViewById(R.id.imgProfileTop)

        cardAccountInfo = findViewById(R.id.cardAccountInfo)
        cardFaceCalibration = findViewById(R.id.cardFaceCalibration)
        tvCalibrationStatus = findViewById(R.id.tvCalibrationStatus)
        cardFaq = findViewById(R.id.cardFaq)
        cardFeedback = findViewById(R.id.cardFeedback)
        cardLogout = findViewById(R.id.cardLogout)

        navHome = findViewById(R.id.navHome)
        navScan = findViewById(R.id.navScan)
        navProfile = findViewById(R.id.navProfile)
    }

    private fun updateCalibrationStatus() {
        if (FaceCalibrationManager.isCalibrated(this)) {
            tvCalibrationStatus.text = "Status: Registered & Calibrated"
            tvCalibrationStatus.setTextColor(android.graphics.Color.parseColor("#80FF90")) // Soft Green
        } else {
            tvCalibrationStatus.text = "Status: Not Registered (Tap to Register)"
            tvCalibrationStatus.setTextColor(android.graphics.Color.parseColor("#B9A9D6"))
        }
    }

    private fun setupCards() {
        imgProfileTop.setOnClickListener {
            startActivity(Intent(this, AccountInfoActivity::class.java))
        }

        cardAccountInfo.setOnClickListener {
            startActivity(Intent(this, AccountInfoActivity::class.java))
        }

        cardFaceCalibration.setOnClickListener {
            showFaceCalibrationDialog()
        }

        cardFaq.setOnClickListener {
            startActivity(Intent(this, FaqActivity::class.java))
        }

        cardFeedback.setOnClickListener {
            startActivity(Intent(this, FeedbackActivity::class.java))
        }

        cardLogout.setOnClickListener {
            showLogoutConfirmation()
        }
    }

    private fun showFaceCalibrationDialog() {
        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or 
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        )

        if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
            val executor = ContextCompat.getMainExecutor(this)
            val biometricPrompt = BiometricPrompt(this, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        startCalibrationScan()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        Toast.makeText(this@SettingsActivity, "Authentication failed: $errString", Toast.LENGTH_SHORT).show()
                    }
                })

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Hardware Biometric Authentication")
                .setSubtitle("Confirm your identity before calibrating Face ID")
                .setNegativeButtonText("Cancel")
                .build()

            biometricPrompt.authenticate(promptInfo)
        } else {
            // Hardware Biometric not available, proceed directly to camera 3D vector calibration
            startCalibrationScan()
        }
    }

    private fun startCalibrationScan() {
        AlertDialog.Builder(this)
            .setTitle("Register / Calibrate Face ID")
            .setMessage("Position your face in front of the camera with a natural, neutral expression.\n\nThis will save your 1434-dimensional 3D spatial vector to verify your identity and calibrate your resting face for maximum mood accuracy.")
            .setPositiveButton("Start Camera Scan") { dialog, _ ->
                dialog.dismiss()
                val intent = Intent(this, ScanActivity::class.java).apply {
                    putExtra("is_calibration_mode", true)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun logoutUser() {

        // 1. Firebase logout
        FirebaseAuthHelper.logout()

        // 2. Google logout (forces account picker next time)
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
            val googleSignInClient = GoogleSignIn.getClient(this, gso)
            googleSignInClient.signOut()
        } catch (_: Exception) { }

        // 3. Spotify cleanup
        try {
            stopService(Intent(this, SpotifyBackgroundService::class.java))
        } catch (_: Exception) { }
        
        SpotifyTokenStorage.clear(this)
        SpotifyRemoteManager.disconnect()

        // 4. Reset player state
        NowPlayingState.currentTrack = null
        NowPlayingState.isPlaying = false
        NowPlayingState.playbackPositionMs = 0L
        NowPlayingState.durationMs = 0L

        // 5. Deep clean local user data & privacy state
        AiAgentChatDialog.clearSessionHistory()
        FaceCalibrationManager.clearCalibration(this)
        MoodRecommendationCache.clear(this)

        getSharedPreferences("moodsync_home_state", MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        getSharedPreferences("moodsync_settings", MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()

        // 6. Redirect to login (clear all backstack)
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        startActivity(intent)
        finish()
    }

    private fun showLogoutConfirmation() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Log out?")
            .setMessage("Are you sure you want to log out of eMoodtune and disconnect Spotify?")
            .setPositiveButton("Log out") { dialog, _ ->
                dialog.dismiss()
                logoutUser()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun setupBottomNav() {
        navHome.setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }

        navScan.setOnClickListener {
            val intent = Intent(this, ScanActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }

        navProfile.setOnClickListener {
            Toast.makeText(this, "Already in Settings", Toast.LENGTH_SHORT).show()
        }
    }
}