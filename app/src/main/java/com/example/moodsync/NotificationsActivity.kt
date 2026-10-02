package com.example.moodsync

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class NotificationsActivity : AppCompatActivity() {

    private lateinit var imgProfile: ImageView
    private lateinit var imgNotifications: ImageView

    private lateinit var tabHome: TextView
    private lateinit var tabHistory: TextView
    private lateinit var tabPlaylist: TextView

    private lateinit var navHome: LinearLayout
    private lateinit var navScan: LinearLayout
    private lateinit var navProfile: LinearLayout

    private lateinit var tvPredictedMood: TextView
    private lateinit var tvNotificationBody: TextView
    private lateinit var btnMusic: TextView
    private lateinit var btnArtists: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notifications)

        bindViews()
        setupNavigation()
        loadMoodNotification()
        ProfileImageLoader.load(imgProfile)
    }

    private fun bindViews() {
        imgProfile = findViewById(R.id.imgProfile)
        imgNotifications = findViewById(R.id.imgNotifications)

        tabHome = findViewById(R.id.tabHome)
        tabHistory = findViewById(R.id.tabHistory)
        tabPlaylist = findViewById(R.id.tabPlaylist)

        navHome = findViewById(R.id.navHome)
        navScan = findViewById(R.id.navScan)
        navProfile = findViewById(R.id.navProfile)

        tvPredictedMood = findViewById(R.id.tvPredictedMood)
        tvNotificationBody = findViewById(R.id.tvNotificationBody)
        btnMusic = findViewById(R.id.btnMusic)
        btnArtists = findViewById(R.id.btnArtists)
    }

    private fun setupNavigation() {
        imgProfile.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        imgNotifications.setOnClickListener {
            // already on notifications page
        }

        tabHome.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }

        tabHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        tabPlaylist.setOnClickListener {
            startActivity(Intent(this, PlaylistActivity::class.java))
        }

        navHome.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }

        navScan.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }

        navProfile.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnMusic.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }

        btnArtists.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }
    }

    private fun loadMoodNotification() {
        val prediction = MoodPredictionEngine(this).predictCurrentSlot()
        val mood = prediction.predictedMood.trim().lowercase(Locale.getDefault())

        val formattedMood = mood.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }

        tvPredictedMood.text = "Predicted mood: $formattedMood"

        tvNotificationBody.text = when (mood) {
            "happy" -> {
                "eMoodtune noticed that your pattern may lean happy right now. New upbeat songs are ready based on your usual listening behavior."
            }

            "sad" -> {
                "eMoodtune noticed that your mood pattern may lean sad. Soft and emotional recommendations are ready for your current emotional rhythm."
            }

            "angry" -> {
                "eMoodtune noticed intense mood signals in your recent pattern. High-energy songs may help match or release that emotion."
            }

            "calm" -> {
                "eMoodtune predicts a calm mood pattern. Relaxing and peaceful recommendations are ready for you."
            }

            else -> {
                "eMoodtune is still learning your emotional pattern. More personalized updates will appear as you continue listening."
            }
        }
    }
}