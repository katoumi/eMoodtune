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
    
    val groupedMoodMixes: LiveData<List<MoodMix>> = rawMoodMixes.switchMap { items ->
        val grouped = items.groupBy { it.emotion.lowercase() }
            .map { (mood, songs) -> MoodMix(mood, songs) }
        MutableLiveData(grouped)
    }

    private val _selectedPlaylistId = MutableLiveData<Long?>(1L)
    val selectedPlaylistId: LiveData<Long?> = _selectedPlaylistId

    private val _selectedMoodName = MutableLiveData<String?>(null)
    val selectedMoodName: LiveData<String?> = _selectedMoodName

    val playlistItems: LiveData<List<PlaylistItem>> = _selectedPlaylistId.switchMap { id ->
        if (id != null) {
            playlistDao.getPlaylistById(id).asLiveData()
        } else {
            // If manual playlist ID is null, we check for mood selection
            _selectedMoodName.switchMap { mood ->
                if (mood != null) {
                    rawMoodMixes.switchMap { items ->
                        val moodSongs = items.filter { it.emotion.equals(mood, ignoreCase = true) }
                            .map { 
                                PlaylistItem(
                                    title = it.songTitle,
                                    artist = it.artist,
                                    duration = it.duration,
                                    emotion = it.emotion,
                                    playlistId = -1L // Special ID for mixes
                                )
                            }
                        MutableLiveData(moodSongs)
                    }
                } else {
                    MutableLiveData(emptyList())
                }
            }
        }
    }

    fun selectPlaylist(id: Long) {
        _selectedMoodName.value = null
        _selectedPlaylistId.value = id
    }

    fun selectMoodMix(mood: String) {
        _selectedPlaylistId.value = null
        _selectedMoodName.value = mood
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
