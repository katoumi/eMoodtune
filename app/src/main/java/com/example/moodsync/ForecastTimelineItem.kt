package com.example.moodsync

data class ForecastTimelineItem(
    val label: String,
    val predictedMood: String,
    val confidence: Int,
    val reason: String
)