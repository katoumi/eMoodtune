package com.example.moodsync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import kotlinx.coroutines.flow.first

class PlaylistViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val playlistDao = database.playlistDao()
    private val playlistMetadataDao = database.playlistMetadataDao()

    val playlists: LiveData<List<PlaylistMetadata>> = playlistMetadataDao.getAllPlaylists().asLiveData()
    
    private val rawMoodMixes: LiveData<List<MoodMixItem>> = database.historyDao().getMoodMixes().asLiveData()

    private val defaultFeelingMixes = listOf(
        MoodMix(mood = "in_love", displayTitle = "In Love?", emoji = "🥰"),
        MoodMix(mood = "hype", displayTitle = "Feeling Hype?", emoji = "⚡"),
        MoodMix(mood = "calm", displayTitle = "Need to Chill?", emoji = "🧘"),
        MoodMix(mood = "hugot", displayTitle = "Heartbroken?", emoji = "🌧️"),
        MoodMix(mood = "happy", displayTitle = "Feel Good Hits", emoji = "😄")
    )
    
    val groupedMoodMixes: LiveData<List<MoodMix>> = rawMoodMixes.switchMap { items ->
        val historyGrouped = items.groupBy { it.emotion.lowercase() }
        val result = defaultFeelingMixes.map { defaultMix ->
            val songs = historyGrouped[defaultMix.mood.lowercase()] ?: emptyList()
            defaultMix.copy(songs = songs)
        }
        MutableLiveData(result)
    }

    private val _selectedPlaylistId = MutableLiveData<Long?>(1L)
    val selectedPlaylistId: LiveData<Long?> = _selectedPlaylistId

    private val _selectedMoodName = MutableLiveData<String?>(null)
    val selectedMoodName: LiveData<String?> = _selectedMoodName

    private val _dynamicMixItems = MutableLiveData<List<PlaylistItem>>(emptyList())

    val playlistItems: LiveData<List<PlaylistItem>> = _selectedPlaylistId.switchMap { id ->
        if (id != null) {
            playlistDao.getPlaylistById(id).asLiveData()
        } else {
            // If manual playlist ID is null, return dynamic feeling mix items
            _dynamicMixItems
        }
    }

    fun selectPlaylist(id: Long) {
        _selectedMoodName.value = null
        _selectedPlaylistId.value = id
    }

    @androidx.camera.core.ExperimentalGetImage
    fun selectMoodMix(mood: String) {
        _selectedPlaylistId.value = null
        _selectedMoodName.value = mood

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = SpotifyBackedRecommendationEngine(getApplication()).getRecommendations(
                    currentMood = mood,
                    timeOfDay = "daytime",
                    limit = 10
                )
                val playlistItems = result.songs.map { song ->
                    PlaylistItem(
                        title = song.title,
                        artist = song.artist,
                        duration = song.duration,
                        emotion = mood,
                        playlistId = -1L
                    )
                }
                _dynamicMixItems.postValue(playlistItems.shuffled())
            } catch (_: Exception) {
            }
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            playlistMetadataDao.insert(PlaylistMetadata(name = name))
        }
    }

    fun renamePlaylist(id: Long, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val p = playlistMetadataDao.getById(id)
            if (p != null) {
                playlistMetadataDao.update(p.copy(name = newName))
            }
        }
    }

    fun deletePlaylist(playlist: PlaylistMetadata) {
        viewModelScope.launch(Dispatchers.IO) {
            playlistMetadataDao.delete(playlist)
            // If the deleted playlist was selected, fallback to My Favorites (1)
            if (_selectedPlaylistId.value == playlist.id) {
                _selectedPlaylistId.postValue(1)
            }
        }
    }

    fun removeSong(song: PlaylistItem) {
        viewModelScope.launch(Dispatchers.IO) {
            playlistDao.delete(song)
        }
    }

    fun clearPlaylist() {
        val currentId = _selectedPlaylistId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val items = playlistDao.getPlaylistById(currentId).first()
            items.forEach { playlistDao.delete(it) }
        }
    }
}
