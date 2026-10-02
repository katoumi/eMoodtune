package com.example.moodsync

data class FutureRecommendationItem(
    val timelineLabel: String,
    val predictedMood: String,
    val confidence: Int,
    val reason: String,
    val songTitle: String,
    val artist: String,
    val score: Double,
    val whyThisFits: String,
    val spotifyUri: String = "",
    val albumArtUrl: String = ""
)