package com.example.moodsync

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

object PlaylistStorage {

    private fun getDao(context: Context) = AppDatabase.getDatabase(context).playlistDao()

    fun savePlaylistItem(context: Context, item: PlaylistItem) {
        runBlocking {
            // Ensure we check within the same playlistId
            val existing = getDao(context).findSong(item.title, item.artist, item.duration)
            if (existing == null || existing.playlistId != item.playlistId) {
                getDao(context).insert(item)
            }
        }
    }

    fun getPlaylist(context: Context): List<PlaylistItem> {
        return runBlocking {
            getDao(context).getAllPlaylist().first()
        }
    }

    fun removeSong(context: Context, song: PlaylistItem) {
        runBlocking {
            getDao(context).delete(song)
        }
    }

    fun clearPlaylist(context: Context) {
        runBlocking {
            getDao(context).deleteAll()
        }
    }
}
