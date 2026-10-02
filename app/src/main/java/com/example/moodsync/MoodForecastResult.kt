package com.example.moodsync

data class MoodForecastResult(
    val predictedMood: String,
    val confidence: Float,
    val reason: String,
    val weeklyDominantMood: String,
    val bestDay: String,
    val bestTimeOfDay: String
)