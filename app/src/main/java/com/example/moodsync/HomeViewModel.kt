package com.example.moodsync

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    data class RecommendationBundle(
        val songs: List<HomeActivity.Song>,
        val trackCache: Map<String, SpotifyTrack>
    )

    private val database = AppDatabase.getDatabase(application)
    private val historyDao = database.historyDao()
    private val playlistDao = database.playlistDao()
    private val playlistMetadataDao = database.playlistMetadataDao()

    val playlists: LiveData<List<PlaylistMetadata>> = playlistMetadataDao.getAllPlaylists().asLiveData()

    private val _recommendationBundle = MutableLiveData<RecommendationBundle>()
    val recommendationBundle: LiveData<RecommendationBundle> = _recommendationBundle

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _moodLabel = MutableLiveData<String>()
    val moodLabel: LiveData<String> = _moodLabel

    private val _currentMood = MutableLiveData<String>()
    val currentMood: LiveData<String> = _currentMood

    private val _isSearchMode = MutableLiveData<Boolean>()
    val isSearchMode: LiveData<Boolean> = _isSearchMode

    private var searchJob: Job? = null

    private fun getRegulationLabel(mood: String): String {
        return when (SpotifyMoodQueryBuilder.normalizeMood(mood)) {
            "in_love" -> "Romantic"
            "hype" -> "High Energy"
            "hugot" -> "Heartbreak Hugot"
            "sad" -> "Comforting"
            "angry" -> "Soothing"
            else -> formatMood(mood)
        }
    }

    fun loadRecommendations(emotion: String, finalMood: String, isManual: Boolean = false) {
        val cached = MoodRecommendationCache.get(finalMood)
        val regulationGoal = getRegulationLabel(finalMood)
        
        if (cached != null) {
            _recommendationBundle.value = RecommendationBundle(cached.songs, cached.trackCache)
            _isLoading.value = false
            
            if (isManual) {
                _moodLabel.value = "Manual Mood: ${formatMood(emotion)} | Goal: $regulationGoal"
            } else {
                _moodLabel.value = "Detected: ${formatMood(emotion)} | Goal: $regulationGoal"
            }
            
            // Still refresh in background if cache is old (> 10 mins)
            val now = System.currentTimeMillis()
            if (now - cached.timestamp < 600_000L) {
                return 
            }
        } else {
            _isLoading.value = true
            _moodLabel.value = "Updating recommendations..."
        }

        _currentMood.value = finalMood
        _isSearchMode.value = isManual

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    SpotifyBackedRecommendationEngine(getApplication()).getRecommendations(
                        currentMood = finalMood,
                        timeOfDay = getCurrentTimeOfDay(),
                        limit = 10
                    )
                }

                _recommendationBundle.value = RecommendationBundle(result.songs, result.trackCache)
                if (isManual) {
                    _moodLabel.value = "Manual Mood: ${formatMood(emotion)} | Goal: $regulationGoal"
                } else {
                    _moodLabel.value = "Detected: ${formatMood(emotion)} | Goal: $regulationGoal"
                }
                
                // Save to persistent cache
                MoodRecommendationCache.set(
                    getApplication(),
                    finalMood,
                    MoodRecommendationCache.CachedResult(result.songs, result.trackCache)
                )
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to load recommendations", e)
                if (_recommendationBundle.value == null || _recommendationBundle.value!!.songs.isEmpty()) {
                    _moodLabel.value = "Matches your mood (Offline)"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun performSearch(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            _isSearchMode.value = false
            return
        }

        val moodOverride = detectMoodKeyword(trimmed)
        if (moodOverride != null) {
            val validMood: String = moodOverride
            _currentMood.value = validMood
            _isSearchMode.value = true
            _moodLabel.value = "Manual Mood: ${formatMood(validMood)}"
            loadRecommendations(validMood, validMood, isManual = true)
            return
        }

        trackGenreFromSearch(trimmed)

        _isSearchMode.value = true
        searchJob = viewModelScope.launch {
            delay(700)
            _isLoading.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    SpotifySearchManager(getApplication()).search(
                        query = trimmed,
                        limit = 10,
                        market = "PH"
                    )
                }
                _recommendationBundle.value = RecommendationBundle(result.songs, result.trackCache)
                _moodLabel.value = "Results for: $trimmed"
            } catch (e: Exception) {
                _moodLabel.value = "Search failed"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun detectMoodKeyword(query: String): String? {
        val q = query.lowercase()
        return when {
            q.contains("in_love") || q.contains("love") || q.contains("romantic") -> "in_love"
            q.contains("hype") || q.contains("workout") || q.contains("energy") -> "hype"
            q.contains("hugot") || q.contains("heartbreak") -> "hugot"
            q == "happy" || q == "joy" -> "happy"
            q == "sad" || q == "down" -> "sad"
            q == "angry" || q == "mad" -> "angry"
            q == "calm" || q == "chill" -> "calm"
            else -> null
        }
    }

    private fun trackGenreFromSearch(query: String) {
        val genres = listOf("hip hop", "rap", "pop", "rock", "indie", "lofi", "jazz", "classical", "acoustic")
        val q = query.lowercase()
        
        // Track regional preference
        val isOPMQuery = q.contains("opm") || q.contains("tagalog") || q.contains("pinoy") || q.contains("p-pop")
        val isIntlQuery = q.contains("international") || q.contains("western") || q.contains("us pop") || q.contains("k-pop")
        
        if (isOPMQuery) UserPreferenceManager.trackRegionInterest(getApplication(), true)
        if (isIntlQuery) UserPreferenceManager.trackRegionInterest(getApplication(), false)

        genres.forEach { genre ->
            if (q.contains(genre)) {
                UserPreferenceManager.trackGenreInterest(getApplication(), genre)
            }
        }
    }

    fun addToPlaylist(song: HomeActivity.Song, emotion: String, playlistId: Long = 1) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = PlaylistItem(
                title = song.title,
                artist = song.artist,
                duration = song.duration,
                emotion = emotion,
                playlistId = playlistId
            )
            playlistDao.insert(item)
        }
    }

    fun saveHistory(item: HistoryItem) {
        viewModelScope.launch(Dispatchers.IO) {
            historyDao.insert(item)
        }
    }

    private fun getCurrentTimeOfDay(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..20 -> "Evening"
            else -> "Night"
        }
    }

    private fun formatMood(mood: String): String {
        return mood.replace("_", " ").split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }
}
