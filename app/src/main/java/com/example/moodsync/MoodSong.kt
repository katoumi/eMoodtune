package com.example.moodsync

data class MoodSong(
    val title: String,
    val artist: String,
    val moods: List<String>,
    val energy: Int = 50,
    val tags: List<String> = emptyList()
)