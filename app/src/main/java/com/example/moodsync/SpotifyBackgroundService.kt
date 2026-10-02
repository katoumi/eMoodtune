package com.example.moodsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * A background service that keeps the Spotify App Remote connection alive
 * throughout the app session, enabling "Instant Play" without switches.
 */
class SpotifyBackgroundService : Service() {

    companion object {
        const val ACTION_STOP_MUSIC = "com.example.moodsync.STOP_MUSIC"
    }

    private val CHANNEL_ID = "spotify_background_channel"
    private val NOTIFICATION_ID = 101

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MUSIC) {
            Log.d("SpotifyService", "Manual STOP action received")
            handleCleanShutdown()
            return START_NOT_STICKY
        }

        Log.d("SpotifyService", "Service started - initiating persistent connection")
        
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Aggressive Warm-up
        SpotifyWakeUpHelper.aggressiveWakeUp(this)
        
        // GIVING SPOTIFY TIME TO BREATHE (600ms)
        Handler(Looper.getMainLooper()).postDelayed({
            // Attempt to connect silently in background
            if (!SpotifyRemoteManager.isConnected()) {
                SpotifyRemoteManager.connect(this, 
                    showAuth = false,
                    onConnected = {
                        Log.d("SpotifyService", "Persistent background bridge established")
                        subscribeToBackgroundPlayerState()
                    },
                    onFailure = { throwable ->
                        Log.e("SpotifyService", "Failed silent connection. Waiting for foreground auth.", throwable)
                    }
                )
            } else {
                subscribeToBackgroundPlayerState()
            }
        }, 600)
        
        return START_NOT_STICKY
    }

    private fun subscribeToBackgroundPlayerState() {
        SpotifyRemoteManager.setBackgroundPlayerStateCallback { state ->
            // Ensure history is logged even if the UI is completely destroyed by the OS
            BackgroundHistoryLogger.checkAndLogHistory(applicationContext, state)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "eMoodtune Background Bridge",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("eMoodtune Active")
            .setContentText("Connected to Spotify for instant playback.")
            .setSmallIcon(R.drawable.emoodtune_logo)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("SpotifyService", "Task removed (App Swiped) - initiating clean shutdown")
        handleCleanShutdown()
    }

    private fun handleCleanShutdown() {
        Log.d("SpotifyService", "Performing AGGRESSIVE STOP sequence")
        
        // 1. Direct Pause via Bridge (Most reliable if still connected)
        if (SpotifyRemoteManager.isConnected()) {
            SpotifyRemoteManager.pause(this, onResult = { success ->
                Log.d("SpotifyService", "Pause command outcome: $success")
                // No need to wait for delay if we have a confirmation
                finalizeShutdown()
            })
        } else {
            // 2. AudioFocus Takeover + Pause Broadcast (System-level fallback)
            SpotifyWakeUpHelper.sendPauseBroadcast(this)
            finalizeShutdown()
        }
        
        // 3. Safety timeout: Force kill if Spotify doesn't respond in 2 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            finalizeShutdown()
        }, 2000)
    }

    private fun finalizeShutdown() {
        if (!SpotifyRemoteManager.isConnected() && !isServiceRunning()) return // Already done
        
        Log.d("SpotifyService", "Finalizing cleanup and stopping service")
        SpotifyRemoteManager.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun isServiceRunning(): Boolean = true // Simplified for scope

    override fun onDestroy() {
        super.onDestroy()
        Log.d("SpotifyService", "Service destroyed - cleaning up remote")
        SpotifyRemoteManager.disconnect()
    }
}
