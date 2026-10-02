package com.example.moodsync

import android.content.Context
import java.util.Calendar

class AdaptiveRecommendationEngine(private val context: Context) {

    fun getAdaptiveRecommendations(
        currentMood: String,
        candidateSongs: List<RecommendationCandidate>,
        limit: Int = 5
    ): List<AdaptiveRecommendation> {
        if (candidateSongs.isEmpty()) return emptyList()

        val history = HistoryStorage.getHistory(context)
            .filter { it.emotion.isNotBlank() }
            .sortedBy { it.epochMillis }

        val currentDay = getCurrentDayOfWeek()
        val currentTime = getCurrentTimeOfDay()

        val prediction = MoodPredictionEngine(context).predictCurrentSlot()
        val predictedMood = prediction.predictedMood

        return candidateSongs.map { candidate ->
            val songStats = analyzeSongHistory(
                history = history,
                songTitle = candidate.songTitle,
                artist = candidate.artist,
                currentMood = currentMood,
                currentDay = currentDay,
                currentTime = currentTime
            )

            val artistStats = analyzeArtistHistory(
                history = history,
                artist = candidate.artist,
                currentMood = currentMood,
                currentDay = currentDay,
                currentTime = currentTime
            )

            val feedbackStats = analyzeRecommendationFeedback(
                history = history,
                songTitle = candidate.songTitle,
                artist = candidate.artist
            )

            val currentMoodFitScore = getMoodFitScore(currentMood, candidate.moodTags)
            val predictedMoodFitScore = getMoodFitScore(predictedMood, candidate.moodTags) * 0.85
            val transitionSupportScore = getTransitionSupportScore(
                songStats = songStats,
                artistStats = artistStats,
                currentMood = currentMood,
                predictedMood = predictedMood
            )

            val songImprovementScore = songStats.improvementRate * 3.0
            val artistImprovementScore = artistStats.improvementRate * 2.0
            val feedbackImprovementScore = feedbackStats.improvementRate * 3.5
            val playBoost = feedbackStats.playRate * 1.4
            val replayBoost = feedbackStats.replayRate * 1.2
            val skipPenalty = feedbackStats.skipRate * 2.2
            val timeFitScore = songStats.timeFitScore
            val dayFitScore = songStats.dayFitScore
            val recencyBoost = songStats.recencyBoost
            val usageConfidence = getUsageConfidence(
                songCount = songStats.totalCount + feedbackStats.totalSessions,
                artistCount = artistStats.totalCount
            )

            val finalScore =
                currentMoodFitScore +
                        predictedMoodFitScore +
                        transitionSupportScore +
                        songImprovementScore +
                        artistImprovementScore +
                        feedbackImprovementScore +
                        playBoost +
                        replayBoost +
                        timeFitScore +
                        dayFitScore +
                        recencyBoost +
                        usageConfidence -
                        skipPenalty

            AdaptiveRecommendation(
                songTitle = candidate.songTitle,
                artist = candidate.artist,
                duration = candidate.duration,
                source = candidate.source,
                score = finalScore,
                reason = buildRecommendationReason(
                    currentMood = currentMood,
                    predictedMood = predictedMood,
                    songStats = songStats,
                    artistStats = artistStats,
                    feedbackStats = feedbackStats,
                    currentMoodFitScore = currentMoodFitScore,
                    predictedMoodFitScore = predictedMoodFitScore
                ),
                matchedMood = currentMood,
                predictedMood = predictedMood,
                expectedEffect = feedbackStats.bestImprovementTransition
                    ?: songStats.bestPositiveTransition
                    ?: artistStats.bestPositiveTransition
                    ?: "No strong effect yet"
            )
        }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun analyzeSongHistory(
        history: List<HistoryItem>,
        songTitle: String,
        artist: String,
        currentMood: String,
        currentDay: String,
        currentTime: String
    ): SongStats {
        val matchingEntries = history.filter {
            it.songTitle.equals(songTitle, ignoreCase = true) &&
                    it.artist.equals(artist, ignoreCase = true)
        }

        if (matchingEntries.isEmpty()) {
            return SongStats()
        }

        var improvedCount = 0
        var totalTransitions = 0
        var sameMoodContextCount = 0
        var timeFitMatches = 0
        var dayFitMatches = 0
        var latestEpoch = 0L

        val positiveTransitions = mutableMapOf<String, Int>()

        for (i in 0 until history.size - 1) {
            val current = history[i]
            val next = history[i + 1]

            if (
                current.songTitle.equals(songTitle, ignoreCase = true) &&
                current.artist.equals(artist, ignoreCase = true)
            ) {
                totalTransitions++

                val effect = compareMoodEffect(current.emotion, next.emotion)
                if (effect == MoodEffect.IMPROVED) {
                    improvedCount++
                    val transitionKey =
                        "${current.emotion.trim().lowercase()} → ${next.emotion.trim().lowercase()}"
                    positiveTransitions[transitionKey] =
                        (positiveTransitions[transitionKey] ?: 0) + 1
                }

                if (current.emotion.equals(currentMood, ignoreCase = true)) {
                    sameMoodContextCount++
                }

                if (current.timeOfDay.equals(currentTime, ignoreCase = true)) {
                    timeFitMatches++
                }

                if (current.dayOfWeek.equals(currentDay, ignoreCase = true)) {
                    dayFitMatches++
                }

                if (current.epochMillis > latestEpoch) {
                    latestEpoch = current.epochMillis
                }
            }
        }

        val improvementRate =
            if (totalTransitions > 0) improvedCount.toDouble() / totalTransitions.toDouble() else 0.0

        val timeFitScore =
            if (matchingEntries.isNotEmpty()) timeFitMatches.toDouble() / matchingEntries.size.toDouble() else 0.0

        val dayFitScore =
            if (matchingEntries.isNotEmpty()) dayFitMatches.toDouble() / matchingEntries.size.toDouble() else 0.0

        return SongStats(
            totalCount = matchingEntries.size,
            improvementRate = improvementRate,
            sameMoodContextCount = sameMoodContextCount,
            timeFitScore = timeFitScore,
            dayFitScore = dayFitScore,
            recencyBoost = calculateRecencyBoost(latestEpoch),
            bestPositiveTransition = positiveTransitions.maxByOrNull { it.value }?.key
        )
    }

    private fun analyzeArtistHistory(
        history: List<HistoryItem>,
        artist: String,
        currentMood: String,
        currentDay: String,
        currentTime: String
    ): ArtistStats {
        if (artist.isBlank()) return ArtistStats()

        val matchingEntries = history.filter {
            it.artist.equals(artist, ignoreCase = true)
        }

        if (matchingEntries.isEmpty()) {
            return ArtistStats()
        }

        var improvedCount = 0
        var totalTransitions = 0
        var sameMoodContextCount = 0
        var timeFitMatches = 0
        var dayFitMatches = 0

        val positiveTransitions = mutableMapOf<String, Int>()

        for (i in 0 until history.size - 1) {
            val current = history[i]
            val next = history[i + 1]

            if (current.artist.equals(artist, ignoreCase = true)) {
                totalTransitions++

                val effect = compareMoodEffect(current.emotion, next.emotion)
                if (effect == MoodEffect.IMPROVED) {
                    improvedCount++
                    val transitionKey =
                        "${current.emotion.trim().lowercase()} → ${next.emotion.trim().lowercase()}"
                    positiveTransitions[transitionKey] =
                        (positiveTransitions[transitionKey] ?: 0) + 1
                }

                if (current.emotion.equals(currentMood, ignoreCase = true)) {
                    sameMoodContextCount++
                }

                if (current.timeOfDay.equals(currentTime, ignoreCase = true)) {
                    timeFitMatches++
                }

                if (current.dayOfWeek.equals(currentDay, ignoreCase = true)) {
                    dayFitMatches++
                }
            }
        }

        val improvementRate =
            if (totalTransitions > 0) improvedCount.toDouble() / totalTransitions.toDouble() else 0.0

        return ArtistStats(
            totalCount = matchingEntries.size,
            improvementRate = improvementRate,
            sameMoodContextCount = sameMoodContextCount,
            timeFitScore =
                if (matchingEntries.isNotEmpty()) timeFitMatches.toDouble() / matchingEntries.size.toDouble() else 0.0,
            dayFitScore =
                if (matchingEntries.isNotEmpty()) dayFitMatches.toDouble() / matchingEntries.size.toDouble() else 0.0,
            bestPositiveTransition = positiveTransitions.maxByOrNull { it.value }?.key
        )
    }

    private fun analyzeRecommendationFeedback(
        history: List<HistoryItem>,
        songTitle: String,
        artist: String
    ): FeedbackStats {
        val sessions = history.filter {
            it.recommendedSongTitle.equals(songTitle, ignoreCase = true) &&
                    it.recommendedArtist.equals(artist, ignoreCase = true) &&
                    it.sessionId.isNotBlank()
        }

        if (sessions.isEmpty()) return FeedbackStats()

        var playedCount = 0
        var skippedCount = 0
        var replayedCount = 0
        var improvedCount = 0

        val improvementTransitions = mutableMapOf<String, Int>()

        sessions.forEach { item ->
            if (item.wasPlayed) playedCount++
            if (item.wasSkipped) skippedCount++
            if (item.wasReplayed) replayedCount++

            if (item.moodBefore.isNotBlank() && item.moodAfter.isNotBlank()) {
                val effect = compareMoodEffect(item.moodBefore, item.moodAfter)
                if (effect == MoodEffect.IMPROVED) {
                    improvedCount++
                    val key =
                        "${item.moodBefore.trim().lowercase()} → ${item.moodAfter.trim().lowercase()}"
                    improvementTransitions[key] =
                        (improvementTransitions[key] ?: 0) + 1
                }
            }
        }

        val total = sessions.size.toDouble()

        return FeedbackStats(
            totalSessions = sessions.size,
            playRate = if (total > 0) playedCount / total else 0.0,
            skipRate = if (total > 0) skippedCount / total else 0.0,
            replayRate = if (total > 0) replayedCount / total else 0.0,
            improvementRate = if (total > 0) improvedCount / total else 0.0,
            bestImprovementTransition = improvementTransitions.maxByOrNull { it.value }?.key
        )
    }

    private fun getMoodFitScore(currentMood: String, moodTags: List<String>): Double {
        val normalizedMood = currentMood.trim().lowercase()
        val normalizedTags = moodTags.map { it.trim().lowercase() }

        if (normalizedTags.isEmpty()) return 0.5

        return when {
            normalizedMood in normalizedTags -> 3.0
            normalizedMood == "sad" && ("calm" in normalizedTags || "uplifting" in normalizedTags) -> 2.4
            normalizedMood == "angry" && ("calm" in normalizedTags || "release" in normalizedTags) -> 2.4
            normalizedMood == "happy" && ("energetic" in normalizedTags || "happy" in normalizedTags) -> 2.4
            normalizedMood == "calm" && ("calm" in normalizedTags || "chill" in normalizedTags) -> 2.4
            else -> 0.8
        }
    }

    private fun getTransitionSupportScore(
        songStats: SongStats,
        artistStats: ArtistStats,
        currentMood: String,
        predictedMood: String
    ): Double {
        val targetTransition = "${currentMood.trim().lowercase()} → ${predictedMood.trim().lowercase()}"

        return when {
            songStats.bestPositiveTransition.equals(targetTransition, ignoreCase = true) -> 2.2
            artistStats.bestPositiveTransition.equals(targetTransition, ignoreCase = true) -> 1.6
            else -> 0.0
        }
    }

    private fun getUsageConfidence(songCount: Int, artistCount: Int): Double {
        val combined = songCount + artistCount
        return when {
            combined >= 12 -> 1.2
            combined >= 8 -> 0.9
            combined >= 4 -> 0.6
            combined >= 1 -> 0.3
            else -> 0.0
        }
    }

    private fun calculateRecencyBoost(latestEpoch: Long): Double {
        if (latestEpoch <= 0L) return 0.0

        val now = System.currentTimeMillis()
        val ageMillis = now - latestEpoch
        val oneDay = 24L * 60L * 60L * 1000L

        return when {
            ageMillis <= oneDay * 2 -> 1.0
            ageMillis <= oneDay * 7 -> 0.7
            ageMillis <= oneDay * 14 -> 0.45
            ageMillis <= oneDay * 30 -> 0.2
            else -> 0.0
        }
    }

    private fun buildRecommendationReason(
        currentMood: String,
        predictedMood: String,
        songStats: SongStats,
        artistStats: ArtistStats,
        feedbackStats: FeedbackStats,
        currentMoodFitScore: Double,
        predictedMoodFitScore: Double
    ): String {
        val transitionKey = "${currentMood.trim().lowercase()} → ${predictedMood.trim().lowercase()}"

        return when {
            feedbackStats.improvementRate >= 0.70 && feedbackStats.bestImprovementTransition != null ->
                "Recommended because this song often gets played and improves mood through ${feedbackStats.bestImprovementTransition.lowercase()}."

            feedbackStats.skipRate >= 0.70 ->
                "Shown with lower confidence because this song is often skipped in your history."

            feedbackStats.replayRate >= 0.50 ->
                "Recommended because you often replay this song after it is suggested."

            songStats.bestPositiveTransition.equals(transitionKey, ignoreCase = true) ->
                "Recommended because this song often supports a ${transitionKey.lowercase()} shift for you."

            artistStats.bestPositiveTransition.equals(transitionKey, ignoreCase = true) ->
                "Recommended because this artist is often linked to a ${transitionKey.lowercase()} shift."

            songStats.improvementRate >= 0.70 && songStats.bestPositiveTransition != null ->
                "Recommended because this song often helps with ${currentMood.lowercase()} moods and is frequently linked to ${songStats.bestPositiveTransition.lowercase()}."

            artistStats.improvementRate >= 0.70 && artistStats.bestPositiveTransition != null ->
                "Recommended because songs by this artist are often linked to ${artistStats.bestPositiveTransition.lowercase()}."

            songStats.sameMoodContextCount >= 2 ->
                "Recommended because you often play this song when you feel ${currentMood.lowercase()}."

            artistStats.sameMoodContextCount >= 2 ->
                "Recommended because this artist matches your recent ${currentMood.lowercase()} listening pattern."

            predictedMoodFitScore >= 2.0 ->
                "Recommended because it fits your current mood and your predicted ${predictedMood.lowercase()} pattern."

            currentMoodFitScore >= 2.4 ->
                "Recommended because it matches your current ${currentMood.lowercase()} mood."

            else ->
                "Recommended based on your overall listening and mood history."
        }
    }

    private fun compareMoodEffect(fromMood: String, toMood: String): MoodEffect {
        val from = fromMood.trim().lowercase()
        val to = toMood.trim().lowercase()

        if (from.isBlank() || to.isBlank()) return MoodEffect.UNCHANGED
        if (from == to) return MoodEffect.UNCHANGED

        val moodRank = mapOf(
            "angry" to 1,
            "sad" to 2,
            "calm" to 3,
            "happy" to 4
        )

        val fromScore = moodRank[from] ?: 0
        val toScore = moodRank[to] ?: 0

        return when {
            toScore > fromScore -> MoodEffect.IMPROVED
            toScore < fromScore -> MoodEffect.WORSENED
            else -> MoodEffect.UNCHANGED
        }
    }

    private fun getCurrentDayOfWeek(): String {
        return when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "Sunday"
            Calendar.MONDAY -> "Monday"
            Calendar.TUESDAY -> "Tuesday"
            Calendar.WEDNESDAY -> "Wednesday"
            Calendar.THURSDAY -> "Thursday"
            Calendar.FRIDAY -> "Friday"
            else -> "Saturday"
        }
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

    data class RecommendationCandidate(
        val songTitle: String,
        val artist: String,
        val duration: String = "",
        val source: String = "local",
        val moodTags: List<String> = emptyList()
    )

    data class AdaptiveRecommendation(
        val songTitle: String,
        val artist: String,
        val duration: String,
        val source: String,
        val score: Double,
        val reason: String,
        val matchedMood: String,
        val predictedMood: String,
        val expectedEffect: String
    )

    data class SongStats(
        val totalCount: Int = 0,
        val improvementRate: Double = 0.0,
        val sameMoodContextCount: Int = 0,
        val timeFitScore: Double = 0.0,
        val dayFitScore: Double = 0.0,
        val recencyBoost: Double = 0.0,
        val bestPositiveTransition: String? = null
    )

    data class ArtistStats(
        val totalCount: Int = 0,
        val improvementRate: Double = 0.0,
        val sameMoodContextCount: Int = 0,
        val timeFitScore: Double = 0.0,
        val dayFitScore: Double = 0.0,
        val bestPositiveTransition: String? = null
    )

    data class FeedbackStats(
        val totalSessions: Int = 0,
        val playRate: Double = 0.0,
        val skipRate: Double = 0.0,
        val replayRate: Double = 0.0,
        val improvementRate: Double = 0.0,
        val bestImprovementTransition: String? = null
    )

    enum class MoodEffect {
        IMPROVED,
        UNCHANGED,
        WORSENED
    }
}