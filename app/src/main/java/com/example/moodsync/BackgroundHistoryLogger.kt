package com.example.moodsync

import android.content.Context
import android.util.Log
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object BackgroundHistoryLogger {

    private const val PREF_HOME_STATE = "moodsync_home_state"
    private const val KEY_LAST_LOGGED_URI = "last_logged_uri"
    
    // We use a dedicated scope since this operates in the background independently of UI lifecycle
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loggingJob: Job? = null
    private var currentTrackUri: String = ""

    fun checkAndLogHistory(context: Context, state: PlayerState) {
        val track = state.track ?: return
        val uri = track.uri
        val songTitle = track.name
        val artist = track.artist.name

        // Guard against empty track metadata during transitions
        if (uri.isBlank() || songTitle.isBlank() || artist.isBlank()) {
            return
        }

        // If track changed or paused, cancel any pending log timer
        if (uri != currentTrackUri || state.isPaused) {
            loggingJob?.cancel()
            currentTrackUri = uri
        }

        // If paused, we do nothing more
        if (state.isPaused) {
            return
        }

        val remainingMsUntilLog = 7000L - state.playbackPosition

        if (remainingMsUntilLog <= 0) {
            // Already past 7 seconds, log immediately
            executeLog(context, uri, songTitle, artist, track.duration)
        } else {
            // Wait until the 7-second mark, then log
            if (loggingJob?.isActive != true) {
                loggingJob = scope.launch {
                    delay(remainingMsUntilLog)
                    executeLog(context, uri, songTitle, artist, track.duration)
                }
            }
        }
    }

    private fun executeLog(context: Context, uri: String, songTitle: String, artist: String, durationMs: Long) {
        val now = System.currentTimeMillis()

        // 1. Prevent duplicate logs for the SAME physical play session of a song
        if (NowPlayingState.hasLoggedCurrentTrack && uri == NowPlayingState.lastLoggedUri) {
            return
        }

        // 2. Prevent rapid re-logging of different songs (cooldown)
        val isSameSong = uri == NowPlayingState.lastLoggedUri
        val isWithinWindow = now - NowPlayingState.lastLoggedTimeMs < 15_000L // 15s window for diff songs

        if (isSameSong && isWithinWindow) {
            return
        }

        NowPlayingState.lastLoggedUri = uri
        NowPlayingState.lastLoggedTimeMs = now
        NowPlayingState.hasLoggedCurrentTrack = true
        
        // Persist the log state so it survives process kill
        context.getSharedPreferences(PREF_HOME_STATE, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_LOGGED_URI, uri)
            .apply()
            
        val effectiveSessionId = if (NowPlayingState.activeSessionId.isNotBlank()) {
            NowPlayingState.activeSessionId
        } else {
            val newId = UUID.randomUUID().toString()
            NowPlayingState.activeSessionId = newId
            newId
        }

        val moodBefore = NowPlayingState.moodBefore
        val source = NowPlayingState.source
        val durationSeconds = (durationMs / 1000L).toInt().coerceAtLeast(0)
        val durationString = secondsToTime(durationSeconds)

        scope.launch {
            try {
                if (NowPlayingState.currentTrackMood == null) {
                    NowPlayingState.currentTrackMood = determineMusicalMood(context, uri) ?: moodBefore
                }
                val moodToLog = NowPlayingState.currentTrackMood!!

                val date = Date(now)
                val displayFormatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                val dayFormatter = SimpleDateFormat("EEEE", Locale.getDefault())
                val hourFormatter = SimpleDateFormat("H", Locale.getDefault())

                val hour = hourFormatter.format(date).toInt()
                val timeOfDay = when (hour) {
                    in 5..11 -> "Morning"
                    in 12..16 -> "Afternoon"
                    in 17..20 -> "Evening"
                    else -> "Night"
                }

                val historyItem = HistoryItem(
                    emotion = moodToLog,
                    songTitle = songTitle,
                    artist = artist,
                    duration = durationString,
                    timestamp = displayFormatter.format(date),
                    epochMillis = now,
                    dayOfWeek = dayFormatter.format(date),
                    timeOfDay = timeOfDay,
                    source = source,
                    moodBefore = moodBefore,
                    sessionId = effectiveSessionId,
                    wasPlayed = true
                )

                // Save to Room DB directly
                HistoryStorage.saveHistoryItem(context, historyItem)
                
                // Track preference
                UserPreferenceManager.trackArtistPlay(context, artist)

                // Sync to cloud
                FirebaseHistorySyncHelper.syncLocalHistoryToCloud(context)
                FirebaseFeedbackSyncHelper.syncFeedbackItem(historyItem)
                
                Log.d("BackgroundHistoryLogger", "Successfully logged history for $songTitle")
            } catch (e: Exception) {
                Log.e("BackgroundHistoryLogger", "Error logging history in background", e)
            }
        }
    }

    private suspend fun determineMusicalMood(context: Context, uri: String): String? {
        val trackId = uri.split(":").lastOrNull() ?: return null
        val token = SpotifySessionManager.getValidAccessToken(context) ?: return null

        val features = SpotifyRepository.getAudioFeatures(token, trackId) ?: return null

        val valence = features.first
        val energy = features.second

        return when {
            valence > 0.5 && energy > 0.5 -> "happy"
            valence < 0.5 && energy < 0.5 -> "sad"
            valence < 0.5 && energy > 0.5 -> "angry"
            valence > 0.5 && energy < 0.5 -> "calm"
            else -> "calm"
        }
    }

    private fun secondsToTime(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", mins, secs)
    }
}
