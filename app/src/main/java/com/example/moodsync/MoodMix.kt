package com.example.moodsync

data class MoodMix(
    val mood: String,
    val songs: List<MoodMixItem> = emptyList(),
    val displayTitle: String = "${mood.replace("_", " ").split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }} Mix",
    val emoji: String = "🎵"
)
