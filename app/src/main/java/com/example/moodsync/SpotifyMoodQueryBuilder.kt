package com.example.moodsync

import android.content.Context
import java.util.Locale

object SpotifyMoodQueryBuilder {

    fun buildQueriesForMood(
        context: Context,
        mood: String,
        timeOfDay: String
    ): List<String> {
        val normalizedMood = normalizeMood(mood)
        val normalizedTime = timeOfDay.trim().lowercase(Locale.getDefault())

        val fallbackQueries = getFallbackQueries(context, normalizedMood, normalizedTime)
        val history = HistoryStorage.getHistory(context)
        
        // Also inject explicitly tracked preferences
        val preferredArtists = UserPreferenceManager.getPreferredArtists(context)
        val preferredGenres = UserPreferenceManager.getPreferredGenres(context)
        
        val moodGenreTag = getMoodGenreTag(normalizedMood)
        
        val explicitPrefQueries = mutableListOf<String>()
        preferredArtists.forEach { artist ->
            explicitPrefQueries.add("$artist $moodGenreTag")
        }
        preferredGenres.forEach { genre ->
            explicitPrefQueries.add("$genre $moodGenreTag")
        }

        if (history.isEmpty()) {
            return (explicitPrefQueries + fallbackQueries)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase(Locale.getDefault()) }
        }

        val artistScores = mutableMapOf<String, Int>()

        history.forEach { item ->
            val artist = item.artist.trim()
            if (artist.isBlank()) return@forEach

            var score = 0

            if (item.wasReplayed) score += 4
            if (item.wasPlayed) score += 2
            if (item.wasSkipped) score -= 3

            val itemMoodBefore = normalizeMood(item.moodBefore)
            val itemMoodAfter = normalizeMood(item.moodAfter)
            val itemEmotion = normalizeMood(item.emotion)

            if (itemMoodAfter.isNotBlank() && itemMoodBefore != itemMoodAfter) {
                score += 3
            }

            if (itemMoodBefore == normalizedMood || itemEmotion == normalizedMood) {
                score += 5
            }

            artistScores[artist] = (artistScores[artist] ?: 0) + score
        }

        val topArtists = artistScores
            .toList()
            .sortedByDescending { it.second }
            .filter { it.second > 0 }
            .map { it.first }
            .take(3)

        val personalizedQueries = topArtists.map { artist ->
            "$artist $moodGenreTag"
        }

        return (personalizedQueries + explicitPrefQueries + fallbackQueries)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.getDefault()) }
            .take(12)
    }

    fun normalizeMood(rawMood: String): String {
        val mood = rawMood.trim().lowercase(Locale.getDefault())

        return when {
            mood.contains("happy") ||
                    mood.contains("joy") ||
                    mood.contains("excited") ||
                    mood.contains("smile") -> "happy"

            mood.contains("sad") ||
                    mood.contains("down") ||
                    mood.contains("lonely") ||
                    mood.contains("depress") -> "sad"

            mood.contains("angry") ||
                    mood.contains("anger") ||
                    mood.contains("mad") ||
                    mood.contains("rage") ||
                    mood.contains("frustrat") ||
                    mood.contains("stress") -> "angry"

            mood.contains("calm") ||
                    mood.contains("neutral") ||
                    mood.contains("relax") ||
                    mood.contains("peace") -> "calm"

            else -> "calm"
        }
    }

    fun displayMood(rawMood: String): String {
        return when (normalizeMood(rawMood)) {
            "happy" -> "happy"
            "sad" -> "sad"
            "angry" -> "angry"
            "calm" -> "calm"
            else -> "calm"
        }
    }

    private fun getMoodGenreTag(mood: String): String {
        return when (normalizeMood(mood)) {
            "happy" -> "upbeat feel good pop"
            "sad" -> "emotional acoustic indie"
            "angry" -> "high energy alternative rock"
            "calm" -> "chill acoustic bedroom pop"
            else -> "chill indie"
        }
    }

    fun getFallbackQueries(context: Context, mood: String, time: String = ""): List<String> {
        val normalizedMood = normalizeMood(mood)
        val normalizedTime = time.trim().lowercase(Locale.getDefault())
        val opmRatio = UserPreferenceManager.getRegionalRatio(context)

        val timeQuery = when (normalizedTime) {
            "morning" -> "morning"
            "afternoon" -> "daytime"
            "evening" -> "evening"
            "night" -> "night"
            else -> ""
        }

        val opmQueries = when (normalizedMood) {
            "happy" -> listOf("SB19 upbeat", "Sarah Geronimo pop", "Parokya ni Edgar", "Eraserheads happy", "OPM upbeat", "Itchyworms")
            "sad" -> listOf("Ben&Ben hugot", "Moira Dela Torre sad", "December Avenue", "Arthur Nery", "OPM sad acoustic", "Hugot hits")
            "angry" -> listOf("Kamikazee rock", "Rivermaya energy", "Slapshock", "Greyhoundz", "Pinoy rock intense", "OPM rock")
            "calm" -> listOf("Clara Benin calm", "Adie acoustic", "Reese Lansangan", "Unique Salonga", "OPM chill", "Tagalog acoustic")
            else -> listOf("OPM top hits", "Pinoy pop", "New Music Friday Philippines")
        }

        val intlQueries = when (normalizedMood) {
            "happy" -> listOf("feel good indie pop", "upbeat pop hits", "dance pop summer", "bright indie pop", "dayglow indie")
            "sad" -> listOf("sad indie acoustic", "emotional indie songs", "heartbreak acoustic", "melancholy bedroom pop")
            "angry" -> listOf("alternative rock high energy", "punk rock energy", "pop punk rage", "workout rock intense")
            "calm" -> listOf("chill acoustic indie", "soft bedroom pop", "calm lofi beats", "relaxing indie acoustic")
            else -> listOf("viral hits global", "global top 50", "new music friday")
        }

        // Calculate sizes based on ratio, ensuring at least 1 of the less-preferred region is included for variety
        val totalTarget = 6
        var opmCount = (totalTarget * opmRatio).toInt()
        var intlCount = totalTarget - opmCount

        if (opmCount == 0) { opmCount = 1; intlCount = totalTarget - 1 }
        if (intlCount == 0) { intlCount = 1; opmCount = totalTarget - 1 }

        val blendedQueries = (opmQueries.shuffled().take(opmCount) + intlQueries.shuffled().take(intlCount)).shuffled()

        return if (timeQuery.isBlank()) {
            blendedQueries
        } else {
            blendedQueries + blendedQueries.take(2).map { "$it $timeQuery" }
        }
    }
}
