package com.example.moodsync

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MoodForecastEngine(private val context: Context) {

    fun generateForecast(): MoodForecastResult {
        val history = HistoryStorage.getHistory(context)
            .filter { it.emotion.isNotBlank() }
            .sortedBy { it.epochMillis }

        if (history.isEmpty()) {
            return MoodForecastResult(
                predictedMood = "Calm",
                confidence = 0f,
                reason = "Not enough history yet",
                weeklyDominantMood = "None",
                bestDay = "Unknown",
                bestTimeOfDay = "Unknown"
            )
        }

        val currentDay = getCurrentDayOfWeek()
        val currentTime = getCurrentTimeOfDay()
        val totalEntries = history.size

        val recentHistory = getRecentEntries(history, 20)
        val lastMood = history.lastOrNull()?.emotion?.trim()?.lowercase().orEmpty()

        val exactMatches = recentHistory.filter {
            it.dayOfWeek.equals(currentDay, ignoreCase = true) &&
                    it.timeOfDay.equals(currentTime, ignoreCase = true)
        }

        val sameTimeMatches = recentHistory.filter {
            it.timeOfDay.equals(currentTime, ignoreCase = true)
        }

        val sameDayMatches = recentHistory.filter {
            it.dayOfWeek.equals(currentDay, ignoreCase = true)
        }

        val transitionMatches = getTransitionTargets(history, lastMood)
        val scores = mutableMapOf<String, Double>()

        addWeightedScores(scores, history, 1.0)
        addWeightedScores(scores, recentHistory, 1.6)
        addWeightedScores(scores, sameDayMatches, 2.0)
        addWeightedScores(scores, sameTimeMatches, 2.5)
        addWeightedScores(scores, exactMatches, 4.2)
        addWeightedScores(scores, transitionMatches, 3.0)

        applyRecencyBoost(scores, recentHistory)
        applyMomentumBoost(scores, recentHistory)
        applyTransitionBoost(scores, history)
        applyDiversityPenalty(scores, history)

        val predictedMood = scores.maxByOrNull { it.value }?.key ?: "Calm"
        val confidence = calculateConfidence(
            scores = scores,
            predictedMood = predictedMood,
            totalEntries = totalEntries,
            exactMatchCount = exactMatches.size
        )

        val weeklyMood = mostFrequentMood(history)
        val bestDay = bestDayForMood(history, weeklyMood)
        val bestTime = bestTimeForMood(history, weeklyMood)

        val reason = buildForecastReason(
            totalEntries = totalEntries,
            currentDay = currentDay,
            currentTime = currentTime,
            exactMatches = exactMatches.size,
            sameDayMatches = sameDayMatches.size,
            sameTimeMatches = sameTimeMatches.size,
            hasTransitionSignal = transitionMatches.isNotEmpty()
        )

        return MoodForecastResult(
            predictedMood = formatMood(predictedMood),
            confidence = confidence,
            reason = reason,
            weeklyDominantMood = formatMood(weeklyMood),
            bestDay = bestDay,
            bestTimeOfDay = bestTime
        )
    }

    private fun addWeightedScores(
        scoreMap: MutableMap<String, Double>,
        list: List<HistoryItem>,
        weight: Double
    ) {
        for (item in list) {
            val mood = item.emotion.trim().lowercase()
            if (mood.isNotBlank()) {
                scoreMap[mood] = (scoreMap[mood] ?: 0.0) + weight
            }
        }
    }

    private fun applyRecencyBoost(
        scoreMap: MutableMap<String, Double>,
        recentHistory: List<HistoryItem>
    ) {
        val last7 = getRecentEntries(recentHistory, 7)

        for ((index, item) in last7.withIndex()) {
            val mood = item.emotion.trim().lowercase()
            if (mood.isBlank()) continue

            val boost = when (index) {
                last7.lastIndex -> 2.0
                last7.lastIndex - 1 -> 1.7
                last7.lastIndex - 2 -> 1.4
                else -> 1.1
            }

            scoreMap[mood] = (scoreMap[mood] ?: 0.0) + boost
        }
    }

    private fun applyMomentumBoost(
        scoreMap: MutableMap<String, Double>,
        recentHistory: List<HistoryItem>
    ) {
        if (recentHistory.size < 3) return

        val last3 = getRecentEntries(recentHistory, 3)
        val moods = last3.map { it.emotion.trim().lowercase() }

        if (moods.distinct().size == 1) {
            val repeatedMood = moods.first()
            scoreMap[repeatedMood] = (scoreMap[repeatedMood] ?: 0.0) + 2.5
        }
    }

    private fun applyTransitionBoost(
        scoreMap: MutableMap<String, Double>,
        history: List<HistoryItem>
    ) {
        val nextLikelyMood = predictNextMood(history) ?: return
        val mood = nextLikelyMood.trim().lowercase()
        if (mood.isNotBlank()) {
            scoreMap[mood] = (scoreMap[mood] ?: 0.0) + 2.2
        }
    }

    private fun applyDiversityPenalty(
        scoreMap: MutableMap<String, Double>,
        history: List<HistoryItem>
    ) {
        val recent = getRecentEntries(history, 10)
        if (recent.isEmpty()) return

        val uniqueCount = recent.map { it.emotion.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .size

        if (uniqueCount >= 4) {
            scoreMap.keys.toList().forEach { key ->
                scoreMap[key] = (scoreMap[key] ?: 0.0) * 0.92
            }
        }
    }

    private fun calculateConfidence(
        scores: Map<String, Double>,
        predictedMood: String,
        totalEntries: Int,
        exactMatchCount: Int
    ): Float {
        val totalScore = scores.values.sum()
        if (totalScore <= 0.0) return 0f

        val predictedScore = scores[predictedMood] ?: 0.0
        var confidence = (predictedScore / totalScore).toFloat()

        when {
            totalEntries < 5 -> confidence *= 0.45f
            totalEntries < 10 -> confidence *= 0.60f
            totalEntries < 20 -> confidence *= 0.78f
        }

        if (exactMatchCount >= 3) confidence += 0.10f
        else if (exactMatchCount >= 1) confidence += 0.05f

        return confidence.coerceIn(0.18f, 0.95f)
    }

    private fun buildForecastReason(
        totalEntries: Int,
        currentDay: String,
        currentTime: String,
        exactMatches: Int,
        sameDayMatches: Int,
        sameTimeMatches: Int,
        hasTransitionSignal: Boolean
    ): String {
        return when {
            totalEntries < 5 ->
                "This forecast is still learning because your history is limited."
            exactMatches >= 3 ->
                "eMoodtune found a strong repeating pattern for $currentDay $currentTime in your history."
            exactMatches >= 1 ->
                "eMoodtune found a partial repeating pattern for $currentDay $currentTime."
            hasTransitionSignal && sameTimeMatches >= 2 ->
                "eMoodtune combined your recent mood transitions with your usual $currentTime pattern."
            hasTransitionSignal ->
                "eMoodtune used your recent mood transitions and overall history to make this forecast."
            sameTimeMatches >= 3 && sameDayMatches >= 3 ->
                "eMoodtune combined your usual $currentTime mood pattern and your usual $currentDay mood pattern."
            sameTimeMatches >= 3 ->
                "eMoodtune used your repeated $currentTime mood pattern."
            sameDayMatches >= 3 ->
                "eMoodtune used your repeated $currentDay mood pattern."
            else ->
                "eMoodtune used your recent mood history and overall pattern to make this forecast."
        }
    }

    private fun getRecentEntries(list: List<HistoryItem>, count: Int): List<HistoryItem> {
        if (list.isEmpty()) return emptyList()
        return if (list.size <= count) list else list.takeLast(count)
    }

    private fun mostFrequentMood(list: List<HistoryItem>): String {
        return list.groupingBy { it.emotion.trim().lowercase() }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: "Unknown"
    }

    private fun bestDayForMood(list: List<HistoryItem>, mood: String): String {
        val filtered = list.filter { it.emotion.equals(mood, ignoreCase = true) }
        return filtered.groupingBy { it.dayOfWeek }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: "No data"
    }

    private fun bestTimeForMood(list: List<HistoryItem>, mood: String): String {
        val filtered = list.filter { it.emotion.equals(mood, ignoreCase = true) }
        return filtered.groupingBy { it.timeOfDay }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: "No data"
    }

    private fun getTransitionTargets(history: List<HistoryItem>, fromMood: String): List<HistoryItem> {
        if (history.size < 2 || fromMood.isBlank()) return emptyList()
        val targets = mutableListOf<HistoryItem>()
        for (i in 0 until history.size - 1) {
            val current = history[i].emotion.trim().lowercase()
            val next = history[i + 1]
            if (current == fromMood) {
                targets.add(next)
            }
        }
        return targets
    }

    private fun predictNextMood(history: List<HistoryItem>): String? {
        if (history.size < 2) return null
        val lastMood = history.lastOrNull()?.emotion?.trim()?.lowercase().orEmpty()
        if (lastMood.isBlank()) return null
        val transitions = mutableMapOf<String, Int>()
        for (i in 0 until history.size - 1) {
            val current = history[i].emotion.trim().lowercase()
            val next = history[i + 1].emotion.trim().lowercase()
            if (current == lastMood && next.isNotBlank()) {
                transitions[next] = (transitions[next] ?: 0) + 1
            }
        }
        return transitions.maxByOrNull { it.value }?.key
    }

    private fun getCurrentDayOfWeek(): String {
        val sdf = SimpleDateFormat("EEEE", Locale.getDefault())
        return sdf.format(Date())
    }

    private fun getCurrentTimeOfDay(): String {
        val hour = SimpleDateFormat("H", Locale.getDefault()).format(Date()).toInt()
        return when (hour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..20 -> "Evening"
            else -> "Night"
        }
    }

    private fun formatMood(mood: String): String {
        return mood.lowercase().replaceFirstChar { it.uppercase() }
    }
}
