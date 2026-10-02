package com.example.moodsync

import android.content.Context

class MoodStatsEngine(private val context: Context) {

    fun getMoodDistribution(): List<StatBar> {
        val history = HistoryStorage.getHistory(context).filter { it.emotion.isNotBlank() }
        val counts = linkedMapOf(
            "Happy" to 0,
            "Calm" to 0,
            "Sad" to 0,
            "Angry" to 0
        )

        history.forEach {
            val key = it.emotion.trim().replaceFirstChar { c -> c.uppercase() }
            if (counts.containsKey(key)) {
                counts[key] = counts[key]!! + 1
            }
        }

        return counts.map { StatBar(it.key, it.value) }
    }

    fun getWeekdayStats(): List<StatBar> {
        val history = HistoryStorage.getHistory(context).filter { it.dayOfWeek.isNotBlank() }
        val order = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        val counts = order.associateWith { 0 }.toMutableMap()

        history.forEach {
            val day = it.dayOfWeek.trim()
            if (counts.containsKey(day)) {
                counts[day] = counts[day]!! + 1
            }
        }

        return order.map { StatBar(it, counts[it] ?: 0) }
    }

    fun getTimeOfDayStats(): List<StatBar> {
        val history = HistoryStorage.getHistory(context).filter { it.timeOfDay.isNotBlank() }
        val order = listOf("Morning", "Afternoon", "Evening", "Night")
        val counts = order.associateWith { 0 }.toMutableMap()

        history.forEach {
            val time = it.timeOfDay.trim()
            if (counts.containsKey(time)) {
                counts[time] = counts[time]!! + 1
            }
        }

        return order.map { StatBar(it, counts[it] ?: 0) }
    }

    fun getDailyMoodLine(): List<LinePoint> {
        val history = HistoryStorage.getHistory(context)
            .filter { it.emotion.isNotBlank() && it.epochMillis > 0L }
            .sortedBy { it.epochMillis }

        return history.mapIndexed { index, item ->
            LinePoint(
                x = index.toFloat(),
                y = moodToScore(item.emotion).toFloat(),
                label = item.timeOfDay.ifBlank { "Entry ${index + 1}" }
            )
        }
    }

    private fun moodToScore(mood: String): Int {
        return when (mood.trim().lowercase()) {
            "angry" -> 1
            "sad" -> 2
            "calm" -> 3
            "happy" -> 4
            else -> 0
        }
    }

    data class StatBar(
        val label: String,
        val value: Int
    )

    data class LinePoint(
        val x: Float,
        val y: Float,
        val label: String
    )
}