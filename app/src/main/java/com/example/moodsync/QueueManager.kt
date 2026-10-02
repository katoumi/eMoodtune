package com.example.moodsync

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

@androidx.camera.core.ExperimentalGetImage
object QueueManager {
    private val _queue = MutableLiveData<MutableList<HomeActivity.Song>>(mutableListOf())
    val queue: LiveData<MutableList<HomeActivity.Song>> = _queue

    fun addToQueue(song: HomeActivity.Song) {
        val current = _queue.value ?: mutableListOf()
        current.add(song)
        _queue.value = current
    }

    fun playNext(): HomeActivity.Song? {
        val current = _queue.value ?: return null
        if (current.isEmpty()) return null
        
        val nextSong = current.removeAt(0)
        _queue.value = current
        return nextSong
    }

    fun clearQueue() {
        _queue.value = mutableListOf()
    }

    fun shuffle() {
        val current = _queue.value ?: return
        if (current.isEmpty()) return
        current.shuffle()
        _queue.value = current
    }

    fun replaceAll(songs: List<HomeActivity.Song>) {
        _queue.value = songs.toMutableList()
    }
    
    fun removeFromQueue(song: HomeActivity.Song) {
        val current = _queue.value ?: return
        current.remove(song)
        _queue.value = current
    }
}
