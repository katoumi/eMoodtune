package com.example.moodsync

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlist")
data class PlaylistItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val artist: String,
    val duration: String,
    val emotion: String,
    val playlistId: Long = 0 // Default to 0, which will be the "My Favorites" playlist
)
