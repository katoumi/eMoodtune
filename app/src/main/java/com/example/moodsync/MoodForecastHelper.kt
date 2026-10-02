package com.example.moodsync

import com.example.moodsync.HistoryItem

object MoodForecastHelper {

    fun predictMood(
        historyList: List<HistoryItem>,
        currentDay: String,
        currentTime: String
    ): String {

        val filtered = historyList.filter {
            it.dayOfWeek == currentDay && it.timeOfDay == currentTime
        }

        if (filtered.isEmpty()) return "neutral"

        val emotionCount = mutableMapOf<String, Int>()

        for (item in filtered) {
            emotionCount[item.emotion] =
                emotionCount.getOrDefault(item.emotion, 0) + 1
        }

        return emotionCount.maxByOrNull { it.value }?.key ?: "neutral"
    }
}