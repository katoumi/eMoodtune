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
            .filter { genre ->
                // Filter out rap/hip-hop from preferred genres when seeking mood regulation (sad/angry/calm)
                if (normalizedMood == "sad" || normalizedMood == "angry" || normalizedMood == "calm") {
                    val g = genre.lowercase()
                    !g.contains("rap") && !g.contains("hip hop") && !g.contains("trap") && !g.contains("drill")
                } else {
                    true
                }
            }
        
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
            mood.contains("in_love") ||
                    mood.contains("love") ||
                    mood.contains("romantic") -> "in_love"

            mood.contains("hype") ||
                    mood.contains("workout") ||
                    mood.contains("energy") -> "hype"

            mood.contains("hugot") ||
                    mood.contains("heartbreak") -> "hugot"

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
        val norm = normalizeMood(rawMood).replace("_", " ")
        return norm.split(" ").joinToString(" ") { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.getDefault()) else c.toString() } }
    }

    private fun getMoodGenreTag(mood: String): String {
        return when (normalizeMood(mood)) {
            "in_love" -> "romantic love songs pop"
            "hype" -> "workout high energy pop rock"
            "hugot" -> "emotional opm hugot acoustic"
            "happy" -> "upbeat feel good pop"
            "sad" -> "comforting acoustic pop" // REGULATION: Lift mood
            "angry" -> "calming soft acoustic" // REGULATION: Soothe anger
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

        // REGULATION: Update base queries to target the intervention goal
        val opmQueries = when (normalizedMood) {
            "in_love" -> listOf("Zack Tabudlo love", "Moira love songs", "Arthur Nery romantic", "TJ Monterde", "OPM love songs")
            "hype" -> listOf("SB19 hype", "Parokya ni Edgar rock", "Kamikazee energetic", "BGYO dance", "OPM workout")
            "hugot" -> listOf("Ben&Ben hugot", "December Avenue heartbreak", "Moira Dela Torre sad", "Silent Sanctuary")
            "happy" -> listOf("SB19 upbeat", "Sarah Geronimo pop", "Parokya ni Edgar", "Eraserheads happy", "OPM upbeat", "Itchyworms")
            "sad" -> listOf("Ben&Ben comforting", "OPM uplifting", "Zack Tabudlo feel good", "Arthur Nery soft", "OPM chill hits")
            "angry" -> listOf("Clara Benin calm", "Adie acoustic", "Reese Lansangan", "OPM relaxing", "Tagalog chill acoustic")
            "calm" -> listOf("Clara Benin calm", "Adie acoustic", "Reese Lansangan", "Unique Salonga", "OPM chill", "Tagalog acoustic")
            else -> listOf("OPM top hits", "Pinoy pop", "New Music Friday Philippines")
        }

        val intlQueries = when (normalizedMood) {
            "in_love" -> listOf("romantic indie pop", "love songs acoustic", "sweet pop hits", "laufey romantic")
            "hype" -> listOf("workout hype pop", "high energy pop punk", "dance hype hits", "gym motivation")
            "hugot" -> listOf("melancholy heartbreak", "emotional acoustic indie", "sad love songs")
            "happy" -> listOf("feel good indie pop", "upbeat pop hits", "dance pop summer", "bright indie pop", "dayglow indie")
            "sad" -> listOf("comforting acoustic", "uplifting pop", "feel good chill", "warm acoustic indie")
            "angry" -> listOf("soothing acoustic", "calm lofi beats", "relaxing ambient", "soft piano chill")
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
