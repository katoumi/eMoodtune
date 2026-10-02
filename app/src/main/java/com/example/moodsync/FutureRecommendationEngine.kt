package com.example.moodsync

import android.content.Context
import kotlin.math.max

object FutureRecommendationEngine {

    fun generateFutureRecommendations(
        context: Context,
        timeline: List<ForecastTimelineItem>,
        songs: List<MoodSong>,
        maxPerTimeline: Int = 1
    ): List<FutureRecommendationItem> {
        if (timeline.isEmpty() || songs.isEmpty()) return emptyList()

        val history = HistoryStorage.getHistory(context)
        val result = mutableListOf<FutureRecommendationItem>()

        for (slot in timeline) {
            val ranked = songs.map { song ->
                val score = scoreSongForFutureSlot(
                    song = song,
                    slot = slot,
                    history = history
                )
                song to score
            }.sortedByDescending { it.second }

            ranked.take(maxPerTimeline).forEach { (song, score) ->
                result.add(
                    FutureRecommendationItem(
                        timelineLabel = slot.label,
                        predictedMood = slot.predictedMood,
                        confidence = slot.confidence,
                        reason = slot.reason,
                        songTitle = song.title,
                        artist = song.artist,
                        score = score,
                        whyThisFits = buildWhyText(song, slot)
                    )
                )
            }
        }

        return result
    }

    private fun scoreSongForFutureSlot(
        song: MoodSong,
        slot: ForecastTimelineItem,
        history: List<HistoryItem>
    ): Double {
        val predictedMood = slot.predictedMood.lowercase()
        val songMoods = song.moods.map { it.lowercase() }

        var score = 0.0

        if (predictedMood in songMoods) {
            score += 35.0
        }

        score += (slot.confidence.coerceIn(0, 100) / 100.0) * 15.0

        val labelLower = slot.label.lowercase()
        val tagsLower = song.tags.map { it.lowercase() }

        if (labelLower.contains("morning") && "morning" in tagsLower) score += 8.0
        if (labelLower.contains("afternoon") && "afternoon" in tagsLower) score += 8.0
        if (labelLower.contains("evening") && "evening" in tagsLower) score += 8.0
        if (labelLower.contains("night") && "night" in tagsLower) score += 8.0

        if (labelLower.contains("monday") && "monday" in tagsLower) score += 5.0
        if (labelLower.contains("tuesday") && "tuesday" in tagsLower) score += 5.0
        if (labelLower.contains("wednesday") && "wednesday" in tagsLower) score += 5.0
        if (labelLower.contains("thursday") && "thursday" in tagsLower) score += 5.0
        if (labelLower.contains("friday") && "friday" in tagsLower) score += 5.0
        if (labelLower.contains("saturday") && "saturday" in tagsLower) score += 5.0
        if (labelLower.contains("sunday") && "sunday" in tagsLower) score += 5.0

        score += energyFitScore(song.energy, predictedMood)
        score += historicalSongFit(song, predictedMood, history)
        score += historicalArtistFit(song.artist, predictedMood, history)
        score += replayBoost(song, history)
        score -= skipPenalty(song, history)

        return score
    }

    private fun energyFitScore(energy: Int, mood: String): Double {
        return when (mood) {
            "sad" -> {
                when {
                    energy in 35..65 -> 10.0
                    energy in 20..80 -> 6.0
                    else -> 2.0
                }
            }
            "angry" -> {
                when {
                    energy in 20..55 -> 10.0
                    energy in 10..70 -> 6.0
                    else -> 2.0
                }
            }
            "happy" -> {
                when {
                    energy in 60..95 -> 10.0
                    energy in 45..100 -> 6.0
                    else -> 2.0
                }
            }
            "calm" -> {
                when {
                    energy in 20..50 -> 10.0
                    energy in 10..65 -> 6.0
                    else -> 2.0
                }
            }
            "neutral" -> {
                when {
                    energy in 35..70 -> 10.0
                    energy in 25..80 -> 6.0
                    else -> 2.0
                }
            }
            else -> 4.0
        }
    }

    private fun historicalSongFit(
        song: MoodSong,
        predictedMood: String,
        history: List<HistoryItem>
    ): Double {
        val matching = history.filter {
            (
                    it.songTitle.equals(song.title, ignoreCase = true) ||
                            it.recommendedSongTitle.equals(song.title, ignoreCase = true)
                    ) && (
                    it.moodBefore.isNotBlank() ||
                            it.moodAfter.isNotBlank() ||
                            it.wasPlayed ||
                            it.wasSkipped ||
                            it.wasReplayed ||
                            it.sessionId.isNotBlank()
                    )
        }

        if (matching.isEmpty()) return 0.0

        var score = 0.0

        matching.forEach { item ->
            val beforeMood = item.moodBefore.ifBlank {
                item.emotion
            }

            if (beforeMood.equals(predictedMood, ignoreCase = true)) {
                if (item.wasPlayed) score += 3.0
                if (item.wasReplayed) score += 5.0
                if (item.wasSkipped) score -= 6.0

                val improvement = moodImprovementValue(beforeMood, item.moodAfter)
                score += improvement * 4.0
            }
        }

        return score.coerceIn(-15.0, 25.0)
    }

    private fun historicalArtistFit(
        artist: String,
        predictedMood: String,
        history: List<HistoryItem>
    ): Double {
        val matching = history.filter {
            (
                    it.artist.equals(artist, ignoreCase = true) ||
                            it.recommendedArtist.equals(artist, ignoreCase = true)
                    ) && (
                    it.moodBefore.isNotBlank() ||
                            it.moodAfter.isNotBlank() ||
                            it.wasPlayed ||
                            it.wasSkipped ||
                            it.wasReplayed ||
                            it.sessionId.isNotBlank()
                    )
        }

        if (matching.isEmpty()) return 0.0

        var score = 0.0

        matching.forEach { item ->
            val beforeMood = item.moodBefore.ifBlank {
                item.emotion
            }

            if (beforeMood.equals(predictedMood, ignoreCase = true)) {
                if (item.wasPlayed) score += 2.0
                if (item.wasReplayed) score += 3.0
                if (item.wasSkipped) score -= 4.0

                val improvement = moodImprovementValue(beforeMood, item.moodAfter)
                score += improvement * 2.5
            }
        }

        return score.coerceIn(-10.0, 18.0)
    }

    private fun replayBoost(song: MoodSong, history: List<HistoryItem>): Double {
        val replays = history.count {
            (
                    it.songTitle.equals(song.title, ignoreCase = true) ||
                            it.recommendedSongTitle.equals(song.title, ignoreCase = true)
                    ) && it.wasReplayed
        }
        return minOf(replays * 1.5, 8.0)
    }

    private fun skipPenalty(song: MoodSong, history: List<HistoryItem>): Double {
        val skips = history.count {
            (
                    it.songTitle.equals(song.title, ignoreCase = true) ||
                            it.recommendedSongTitle.equals(song.title, ignoreCase = true)
                    ) && it.wasSkipped
        }
        return minOf(skips * 2.0, 10.0)
    }

    private fun moodImprovementValue(before: String?, after: String?): Double {
        if (before.isNullOrBlank() || after.isNullOrBlank()) return 0.0

        val moodScore = mapOf(
            "angry" to 1,
            "sad" to 2,
            "neutral" to 3,
            "calm" to 4,
            "happy" to 5
        )

        val beforeValue = moodScore[before.lowercase()] ?: 3
        val afterValue = moodScore[after.lowercase()] ?: 3

        return max((afterValue - beforeValue).toDouble(), -1.0)
    }

    private fun buildWhyText(song: MoodSong, slot: ForecastTimelineItem): String {
        return "Fits ${slot.predictedMood.lowercase()} mood, ${slot.label.lowercase()}, and learned feedback patterns."
    }
}