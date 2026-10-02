package com.example.moodsync

import android.content.Context
import java.util.Calendar

class MoodPredictionEngine(private val context: Context) {

    fun predictCurrentSlot(): PredictionResult {
        val history = getCleanHistory()

        if (history.isEmpty()) {
            return PredictionResult(
                predictedMood = "calm",
                confidence = 0.20f,
                confidenceLabel = "Building",
                reason = "eMoodtune is still learning your patterns. Predictions will improve as you listen more."
            )
        }

        return predictSlot(
            history = history,
            targetDay = getCurrentDayOfWeek(),
            targetTime = getCurrentTimeOfDay()
        )
    }

    fun generateTimeline(daysAhead: Int = 3): List<ForecastPoint> {
        val history = getCleanHistory()

        if (history.isEmpty()) {
            return emptyList()
        }

        val result = mutableListOf<ForecastPoint>()
        val baseCalendar = Calendar.getInstance()
        val slots = listOf("Morning", "Afternoon", "Evening", "Night")

        for (dayOffset in 0..daysAhead) {
            val targetCalendar = baseCalendar.clone() as Calendar
            targetCalendar.add(Calendar.DAY_OF_YEAR, dayOffset)

            val targetDay = getDayOfWeek(targetCalendar)

            val dayLabel = when (dayOffset) {
                0 -> "Today"
                1 -> "Tomorrow"
                else -> targetDay
            }

            for (slot in slots) {
                val prediction = predictSlot(
                    history = history,
                    targetDay = targetDay,
                    targetTime = slot
                )

                result.add(
                    ForecastPoint(
                        dayLabel = dayLabel,
                        dayOfWeek = targetDay,
                        timeOfDay = slot,
                        predictedMood = prediction.predictedMood,
                        confidence = prediction.confidence,
                        confidenceLabel = prediction.confidenceLabel,
                        reason = prediction.reason
                    )
                )
            }
        }

        return result
    }

    private fun getCleanHistory(): List<HistoryItem> {
        return HistoryStorage.getHistory(context)
            .filter { it.emotion.isNotBlank() }
            .sortedBy { it.epochMillis }
    }

    private fun predictSlot(
        history: List<HistoryItem>,
        targetDay: String,
        targetTime: String
    ): PredictionResult {
        val totalEntries = history.size
        val recentHistory = history.takeLast(30)
        val lastMood = history.lastOrNull()?.emotion?.trim()?.lowercase().orEmpty()

        val exactMatches = recentHistory.filter {
            it.dayOfWeek.equals(targetDay, ignoreCase = true) &&
                    it.timeOfDay.equals(targetTime, ignoreCase = true)
        }

        val sameDayMatches = recentHistory.filter {
            it.dayOfWeek.equals(targetDay, ignoreCase = true)
        }

        val sameTimeMatches = recentHistory.filter {
            it.timeOfDay.equals(targetTime, ignoreCase = true)
        }

        val transitionMatches = getTransitionTargets(history, lastMood)

        val scores = mutableMapOf<String, Double>()

        addWeightedScores(scores, history, 1.0)
        addWeightedScores(scores, recentHistory, 1.8)
        addWeightedScores(scores, sameDayMatches, 2.2)
        addWeightedScores(scores, sameTimeMatches, 2.6)
        addWeightedScores(scores, exactMatches, 4.5)
        addWeightedScores(scores, transitionMatches, 3.0)

        applyRecencyBoost(scores, recentHistory)
        applyMomentumBoost(scores, recentHistory)
        applyTransitionBoost(scores, history)
        applyMoodAfterLearning(scores, history)
        applyDiversityPenalty(scores, history)

        val predictedMood = scores.maxByOrNull { it.value }?.key ?: "calm"

        val confidence = calculateConfidence(
            scores = scores,
            predictedMood = predictedMood,
            totalEntries = totalEntries,
            exactMatchCount = exactMatches.size
        )

        return PredictionResult(
            predictedMood = predictedMood,
            confidence = confidence,
            confidenceLabel = getConfidenceLabel(confidence, totalEntries),
            reason = buildReason(
                totalEntries = totalEntries,
                currentDay = targetDay,
                currentTime = targetTime,
                exactMatches = exactMatches.size,
                sameDayMatches = sameDayMatches.size,
                sameTimeMatches = sameTimeMatches.size,
                hasTransitionSignal = transitionMatches.isNotEmpty()
            )
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
        val last10 = recentHistory.takeLast(10)

        for ((index, item) in last10.withIndex()) {
            val mood = item.emotion.trim().lowercase()
            if (mood.isBlank()) continue

            val boost = 1.0 + (index.toDouble() / last10.size.coerceAtLeast(1)) * 2.0
            scoreMap[mood] = (scoreMap[mood] ?: 0.0) + boost
        }
    }

    private fun applyMomentumBoost(
        scoreMap: MutableMap<String, Double>,
        recentHistory: List<HistoryItem>
    ) {
        val last3 = recentHistory.takeLast(3)
        if (last3.size < 3) return

        val moods = last3.map { it.emotion.trim().lowercase() }

        if (moods.distinct().size == 1) {
            val repeatedMood = moods.first()
            scoreMap[repeatedMood] = (scoreMap[repeatedMood] ?: 0.0) + 3.0
        }
    }

    private fun applyTransitionBoost(
        scoreMap: MutableMap<String, Double>,
        history: List<HistoryItem>
    ) {
        val nextLikelyMood = predictNextMood(history) ?: return
        val mood = nextLikelyMood.trim().lowercase()

        if (mood.isNotBlank()) {
            scoreMap[mood] = (scoreMap[mood] ?: 0.0) + 2.5
        }
    }

    private fun applyMoodAfterLearning(
        scoreMap: MutableMap<String, Double>,
        history: List<HistoryItem>
    ) {
        history.forEach { item ->
            val afterMood = item.moodAfter.trim().lowercase()

            if (afterMood.isBlank()) {
                return@forEach
            }

            if (item.wasPlayed || item.wasReplayed) {
                scoreMap[afterMood] = (scoreMap[afterMood] ?: 0.0) + 2.5
            }

            if (item.wasReplayed) {
                scoreMap[afterMood] = (scoreMap[afterMood] ?: 0.0) + 1.5
            }

            if (item.wasSkipped) {
                val beforeMood = item.moodBefore.trim().lowercase()

                if (beforeMood.isNotBlank()) {
                    scoreMap[beforeMood] = (scoreMap[beforeMood] ?: 0.0) - 1.0
                }
            }
        }
    }

    private fun applyDiversityPenalty(
        scoreMap: MutableMap<String, Double>,
        history: List<HistoryItem>
    ) {
        val recent = history.takeLast(10)
        if (recent.isEmpty()) return

        val uniqueCount = recent
            .map { it.emotion.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .size

        if (uniqueCount >= 4) {
            scoreMap.keys.toList().forEach { key ->
                scoreMap[key] = (scoreMap[key] ?: 0.0) * 0.94
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

        if (totalScore <= 0.0) {
            return 0.20f
        }

        val predictedScore = scores[predictedMood] ?: 0.0
        var confidence = (predictedScore / totalScore).toFloat()

        when {
            exactMatchCount >= 4 -> confidence += 0.18f
            exactMatchCount >= 2 -> confidence += 0.10f
            exactMatchCount >= 1 -> confidence += 0.05f
        }

        confidence *= when {
            totalEntries < 5 -> 0.45f
            totalEntries < 10 -> 0.65f
            totalEntries < 20 -> 0.80f
            else -> 1.0f
        }

        return confidence.coerceIn(0.20f, 0.95f)
    }

    private fun buildReason(
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
                "eMoodtune is still learning your patterns. Predictions will improve as you listen more."

            exactMatches >= 3 ->
                "You are usually in this mood on $currentDay $currentTime based on your past sessions."

            exactMatches >= 1 ->
                "Your past $currentDay $currentTime sessions show a similar mood pattern."

            hasTransitionSignal && sameTimeMatches >= 2 ->
                "Your recent mood changes and your usual $currentTime pattern point to this mood."

            hasTransitionSignal ->
                "Your recent listening sessions suggest this mood shift."

            sameTimeMatches >= 3 && sameDayMatches >= 3 ->
                "Both your $currentDay and $currentTime patterns align with this mood."

            sameTimeMatches >= 3 ->
                "You tend to feel this way during $currentTime."

            sameDayMatches >= 3 ->
                "You often feel this way on $currentDay."

            else ->
                "Based on your recent listening behavior and mood patterns."
        }
    }

    private fun getTransitionTargets(
        history: List<HistoryItem>,
        fromMood: String
    ): List<HistoryItem> {
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
        return getDayOfWeek(Calendar.getInstance())
    }

    private fun getCurrentTimeOfDay(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        return when {
            hour in 5..11 -> "Morning"
            hour in 12..16 -> "Afternoon"
            hour in 17..20 -> "Evening"
            else -> "Night"
        }
    }

    private fun getDayOfWeek(calendar: Calendar): String {
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "Sunday"
            Calendar.MONDAY -> "Monday"
            Calendar.TUESDAY -> "Tuesday"
            Calendar.WEDNESDAY -> "Wednesday"
            Calendar.THURSDAY -> "Thursday"
            Calendar.FRIDAY -> "Friday"
            else -> "Saturday"
        }
    }

    private fun getConfidenceLabel(
        confidence: Float,
        totalEntries: Int
    ): String {
        return when {
            totalEntries < 5 -> "Building"
            confidence < 0.35f -> "Low"
            confidence < 0.60f -> "Moderate"
            confidence < 0.80f -> "High"
            else -> "Very High"
        }
    }

    data class PredictionResult(
        val predictedMood: String,
        val confidence: Float,
        val confidenceLabel: String,
        val reason: String
    )

    data class ForecastPoint(
        val dayLabel: String,
        val dayOfWeek: String,
        val timeOfDay: String,
        val predictedMood: String,
        val confidence: Float,
        val confidenceLabel: String,
        val reason: String
    )
}