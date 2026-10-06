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
    
    // Dedicated scope for background operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var loggingJob: Job? = null
    private var currentTrackUri: String = ""
    private var currentSessionId: String = ""
    private var hasLoggedCurrent: Boolean = false

    fun checkAndLogHistory(context: Context, state: PlayerState) {
        val track = state.track ?: return
        val uri = track.uri
        val songTitle = track.name
        val artist = track.artist.name

        // GUARD: Ignore empty transition states
        if (uri.isBlank() || songTitle.isBlank() || artist.isBlank()) {
            return
        }

        // 1. Detect New Track
        if (uri != currentTrackUri) {
            Log.d("BackgroundHistoryLogger", "New track detected: $songTitle")
            loggingJob?.cancel()
            currentTrackUri = uri
            currentSessionId = UUID.randomUUID().toString()
            hasLoggedCurrent = false
            
            // Sync with global state for UI skips
            NowPlayingState.activeSessionId = currentSessionId
            NowPlayingState.currentTrackMood = null 
        }

        // 2. Pause/Logged Guard
        if (state.isPaused || hasLoggedCurrent) {
            loggingJob?.cancel()
            return
        }

        // 3. 0-Second Timer Logic (Log Instantly)
        executeLog(context, track)
    }

    private fun executeLog(context: Context, track: com.spotify.protocol.types.Track) {
        if (hasLoggedCurrent) return
        hasLoggedCurrent = true

        val now = System.currentTimeMillis()
        val uri = track.uri
        val songTitle = track.name
        val artist = track.artist.name

        // Persist the log state so it survives process kill
        context.getSharedPreferences(PREF_HOME_STATE, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_LOGGED_URI, uri)
            .apply()

        val source = NowPlayingState.source
        val durationSeconds = (track.duration / 1000L).toInt().coerceAtLeast(0)
        val durationString = secondsToTime(durationSeconds)
        val sessionId = currentSessionId

        // 100% Sync with HomeActivity: Read the active mood directly from SharedPreferences
        val prefs = context.getSharedPreferences(PREF_HOME_STATE, Context.MODE_PRIVATE)
        val activeMood = prefs.getString("last_final_mood", "calm") ?: "calm"

        scope.launch {
            try {
                // We use the active mood as the primary emotion for the history log.
                // This guarantees that if the user scans "Sad", the history saves "Sad".
                val moodToLog = activeMood
                val moodBefore = activeMood

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
                    sessionId = sessionId,
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

    private fun secondsToTime(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", mins, secs)
    }
}
