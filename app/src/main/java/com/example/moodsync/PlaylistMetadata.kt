package com.example.moodsync

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlist_metadata")
data class PlaylistMetadata(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)
