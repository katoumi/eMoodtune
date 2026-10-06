package com.example.moodsync

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history")
data class HistoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val emotion: String,
    val songTitle: String,
    val artist: String,
    val duration: String,
    val timestamp: String,
    val epochMillis: Long,
    val dayOfWeek: String,
    val timeOfDay: String,
    val source: String,
    val moodBefore: String = "",
    val moodAfter: String = "",
    val recommendedSongTitle: String = "",
    val recommendedArtist: String = "",
    val recommendationSource: String = "",
    val wasPlayed: Boolean = false,
    val wasSkipped: Boolean = false,
    val wasReplayed: Boolean = false,
    val sessionId: String = ""
)

data class MoodMixItem(
    val songTitle: String,
    val artist: String,
    val duration: String,
    val emotion: String,
    val playCount: Int
)
