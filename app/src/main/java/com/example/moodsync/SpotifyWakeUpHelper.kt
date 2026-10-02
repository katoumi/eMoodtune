package com.example.moodsync

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.util.Log

object SpotifyWakeUpHelper {

    private const val SPOTIFY_PACKAGE = "com.spotify.music"
    private const val SPOTIFY_RECEIVER = "com.spotify.mediasession.mediasession.receiver.MediaButtonReceiver"

    /**
     * Nudges Spotify's background engine awake using a multi-stage sequence.
     * Stage 1: Play-Pause sequence to activate MediaSession.
     * Stage 2: Binding Intent to force process into memory.
     * Stage 3: Active broadcast for general availability.
     */
    fun aggressiveWakeUp(context: Context) {
        try {
            // Stage 1: MediaButton Sequence with Intent.FLAG_INCLUDE_STOPPED_PACKAGES
            val playIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
            playIntent.component = ComponentName(SPOTIFY_PACKAGE, SPOTIFY_RECEIVER)
            playIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES) // Bypass hibernation
            
            playIntent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            context.sendOrderedBroadcast(playIntent, null)
            playIntent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
            context.sendOrderedBroadcast(playIntent, null)
            
            val pauseIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
            pauseIntent.component = ComponentName(SPOTIFY_PACKAGE, SPOTIFY_RECEIVER)
            pauseIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            
            pauseIntent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            context.sendOrderedBroadcast(pauseIntent, null)
            pauseIntent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            context.sendOrderedBroadcast(pauseIntent, null)

            // Stage 2: Binding Nudge (Constructive)
            val warmIntent = Intent("com.spotify.music.service.SPOTIFY_SERVICE")
            warmIntent.setPackage(SPOTIFY_PACKAGE)
            warmIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            context.sendBroadcast(warmIntent) 
            
            Log.d("SpotifyWakeUp", "Constructive wake-up sequence completed with stop-flags")
        } catch (e: Exception) {
            Log.e("SpotifyWakeUp", "Aggressive wake-up failed", e)
        }
    }

    /**
     * Safety method to force Spotify to the front if background bridge fails.
     */
    fun forceOpenSpotify(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    /**
     * Aggressively forces all music apps (including Spotify) to stop.
     * Uses AudioFocus takeover as the primary method, with broadcasts as backup.
     */
    fun sendPauseBroadcast(context: Context) {
        try {
            // 1. AUDIO FOCUS TAKEOVER (Modern API for Android 8.0+)
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            audioManager?.let { am ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener { } // Dummy listener
                        .build()

                    val result = am.requestAudioFocus(focusRequest)
                    Log.d("SpotifyWakeUp", "Modern AudioFocus takeover result: $result")
                    am.abandonAudioFocusRequest(focusRequest)
                } else {
                    // Legacy fallback
                    val result = am.requestAudioFocus(
                        null,
                        android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    )
                    Log.d("SpotifyWakeUp", "Legacy AudioFocus takeover result: $result")
                    am.abandonAudioFocus(null)
                }
            }

            // 2. Broadcast Fallback (Redundant but safe)
            val pauseIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
            pauseIntent.component = ComponentName(SPOTIFY_PACKAGE, SPOTIFY_RECEIVER)
            
            pauseIntent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            context.sendOrderedBroadcast(pauseIntent, null)
            pauseIntent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            context.sendOrderedBroadcast(pauseIntent, null)

            // 3. KEYCODE_MEDIA_STOP fallback
            val stopIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
            stopIntent.component = ComponentName(SPOTIFY_PACKAGE, SPOTIFY_RECEIVER)
            stopIntent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP))
            context.sendOrderedBroadcast(stopIntent, null)
            stopIntent.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP))
            context.sendOrderedBroadcast(stopIntent, null)
            
            Log.d("SpotifyWakeUp", "Hard-stop pause/stop sequence completed")
        } catch (e: Exception) {
            Log.e("SpotifyWakeUp", "Failed to force stop music", e)
        }
    }

    /**
     * Legacy helper for smaller nudges.
     */
    fun silentWakeUp(context: Context) {
        aggressiveWakeUp(context)
    }
}
