package com.example.moodsync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.random.Random

class SpotifyBackedRecommendationEngine(
    private val context: Context
) {

    data class Result(
        val songs: List<HomeActivity.Song>,
        val trackCache: Map<String, SpotifyTrack>
    )

    suspend fun getRecommendations(
        currentMood: String,
        timeOfDay: String,
        limit: Int = 7
    ): Result = coroutineScope {
        val normalizedMood = SpotifyMoodQueryBuilder.normalizeMood(currentMood)

        val result = withTimeoutOrNull(10000) {
            fetchRemoteRecommendations(normalizedMood, timeOfDay, limit)
        }

        if (result == null) {
            Log.w("SpotifySearch", "Recommendation timeout for mood=$normalizedMood - triggering fallback")
            fallbackLocal(normalizedMood)
        } else {
            result
        }
    }

    private suspend fun fetchRemoteRecommendations(
        normalizedMood: String,
        timeOfDay: String,
        limit: Int
    ): Result = coroutineScope {
        val safeLimit = limit.coerceIn(1, 10)
        val accessToken = SpotifySessionManager.getValidAccessToken(context)

        if (accessToken.isNullOrBlank()) {
            return@coroutineScope fallbackLocal(normalizedMood)
        }

        val queries = SpotifyMoodQueryBuilder.buildQueriesForMood(
            context = context,
            mood = normalizedMood,
            timeOfDay = timeOfDay
        ).distinct().shuffled().take(2) // Increased from 1 to 2 for more diversity

        val fetchedCandidates = mutableListOf<SpotifyMoodCandidate>()

        // 1. Technical Reco (Accuracy-focused, now fetching 20)
        val technicalTask = async { getTechnicalRecommendations(accessToken, normalizedMood, 20) }

        // 2. Discovery Searches (Variety-focused, now fetching 15 per query)
        val discoveryTasks = queries.map { query ->
            async {
                try {
                    SpotifyMoodSearchRepository.searchTracks(
                        accessToken = accessToken,
                        query = query,
                        limit = 15,
                        market = "PH"
                    )
                } catch (_: Exception) {
                    emptyList<SpotifyMoodCandidate>()
                }
            }
        }

        // Parallel execution
        fetchedCandidates.addAll(technicalTask.await())
        discoveryTasks.forEach { fetchedCandidates.addAll(it.await()) }

        if (fetchedCandidates.isEmpty()) {
            return@coroutineScope fallbackLocal(normalizedMood)
        }

        val history = HistoryStorage.getHistory(context)

        val recentPlayedKeys = history
            .take(25)
            .map { globalSongKey(it.songTitle, it.artist) }
            .toSet()

        val filtered = fetchedCandidates.filter {
            globalSongKey(it.name, it.artist) !in recentPlayedKeys
        }.ifEmpty {
            fetchedCandidates
        }

        val scored = dedupeCandidates(filtered)
            .map { candidate ->
                candidate to calculateAdaptiveScore(
                    candidate = candidate,
                    currentMood = normalizedMood,
                    timeOfDay = timeOfDay,
                    history = history
                )
            }
            .sortedByDescending { it.second }

        // First pass: High-quality matches
        val highQualityMatches = scored.filter { (candidate, _) ->
            moodFitScore(candidate, normalizedMood) >= 8.0
        }.map { it.first }

        var selectedCandidates = diversifyByArtist(highQualityMatches, maxPerArtist = 2)
            .shuffled()
            .take(safeLimit)

        // Safety Fill: If we have fewer than safeLimit, fill from the rest of the deduplicated pool
        if (selectedCandidates.size < safeLimit) {
            val alreadySelectedUris = selectedCandidates.map { it.uri }.toSet()
            val remainingCandidates = scored
                .map { it.first }
                .filter { it.uri !in alreadySelectedUris }
            
            val fillCount = safeLimit - selectedCandidates.size
            val filler = remainingCandidates.take(fillCount)
            selectedCandidates = selectedCandidates + filler
            
            Log.d("SpotifySearch", "Safety fill triggered: Added ${filler.size} songs for $normalizedMood")
        }

        if (selectedCandidates.isEmpty()) {
            return@coroutineScope fallbackLocal(normalizedMood)
        }

        val selectedSongs = selectedCandidates.map { candidate ->
            HomeActivity.Song(
                title = candidate.name,
                artist = candidate.artist,
                duration = candidate.durationText(),
                reason = buildReason(
                    candidate = candidate,
                    history = history,
                    mood = normalizedMood,
                    timeOfDay = timeOfDay
                )
            )
        }

        val cache = mutableMapOf<String, SpotifyTrack>()

        selectedCandidates.forEach { candidate ->
            val track = candidate.toSpotifyTrack()

            cache[globalSongKey(candidate.name, candidate.artist)] = track
            cache[globalSongKey(track.name, track.artist)] = track
            cache[track.uri] = track
        }

        Result(
            songs = selectedSongs,
            trackCache = cache
        )
    }

    private fun calculateAdaptiveScore(
        candidate: SpotifyMoodCandidate,
        currentMood: String,
        timeOfDay: String,
        history: List<HistoryItem>
    ): Double {
        val mood = SpotifyMoodQueryBuilder.normalizeMood(currentMood)
        val artist = candidate.artist.trim()
        val title = candidate.name.trim()

        var score = candidate.popularity * 0.35

        score += moodFitScore(candidate, mood)
        score += timeFitScore(candidate, timeOfDay)

        history.forEach { item ->
            val sameSong =
                item.songTitle.equals(title, ignoreCase = true) &&
                        item.artist.equals(artist, ignoreCase = true)

            val sameArtist = item.artist.equals(artist, ignoreCase = true)

            if (sameSong) {
                if (item.wasPlayed) score += 8.0
                if (item.wasReplayed) score += 14.0
                if (item.wasSkipped) score -= 18.0

                if (isImproved(item)) score += 22.0
                if (isWorsened(item)) score -= 20.0
            }

            if (sameArtist) {
                if (item.wasPlayed) score += 2.0
                if (item.wasReplayed) score += 4.0
                if (item.wasSkipped) score -= 5.0

                if (isImproved(item)) score += 8.0
                if (isWorsened(item)) score -= 7.0
            }

            val itemMoodBefore = SpotifyMoodQueryBuilder.normalizeMood(item.moodBefore)

            if (
                itemMoodBefore == mood &&
                item.recommendedArtist.equals(artist, ignoreCase = true) &&
                isImproved(item)
            ) {
                score += 12.0
            }
        }

        val recentlyPlayedSameArtist = history.take(10).count {
            it.artist.equals(artist, ignoreCase = true)
        }

        score -= recentlyPlayedSameArtist * 3.0

        val recentlyPlayedSameSong = history.take(15).any {
            it.songTitle.equals(title, ignoreCase = true) &&
                    it.artist.equals(artist, ignoreCase = true)
        }

        if (recentlyPlayedSameSong) {
            score -= 25.0
        }

        score += Random.nextDouble() * 5.0

        return score
    }

    private fun moodFitScore(
        candidate: SpotifyMoodCandidate,
        mood: String
    ): Double {
        val normalizedMood = SpotifyMoodQueryBuilder.normalizeMood(mood)
        val title = candidate.name.lowercase(Locale.getDefault())
        val artist = candidate.artist.lowercase(Locale.getDefault())

        return when (normalizedMood) {
            "in_love" -> when {
                containsAny(title, listOf("love", "forever", "always", "yours", "sweet", "heart", "mine", "kiss", "marry", "angel", "pasilyo", "palagi")) -> 20.0
                containsAny(artist, listOf("laufey", "zack tabudlo", "moira", "arthur nery", "tj monterde", "stephen sanchez", "sunkissed lola")) -> 16.0
                containsAny(title, listOf("rage", "fight", "sad", "die", "kill", "pain", "break")) -> -15.0
                else -> 10.0
            }

            "hype" -> when {
                containsAny(title, listOf("hype", "dance", "run", "power", "fire", "gento", "pantropiko", "party", "jump", "bang", "light")) -> 20.0
                containsAny(artist, listOf("sb19", "bini", "the weeknd", "bruno mars", "paramore", "olivia rodrigo", "parokya")) -> 16.0
                containsAny(title, listOf("sleep", "calm", "sad", "cry", "quiet", "slow")) -> -15.0
                else -> 10.0
            }

            "hugot" -> when {
                containsAny(title, listOf("hugot", "sana", "dati", "paubaya", "luha", "sakit", "iwan", "glimpse", "night we met", "as the world")) -> 20.0
                containsAny(artist, listOf("ben&ben", "december avenue", "moira", "silent sanctuary", "joji", "munimuni", "zack tabudlo")) -> 16.0
                containsAny(title, listOf("party", "dance", "hype", "workout", "jump")) -> -15.0
                else -> 10.0
            }

            "happy" -> when {
                containsAny(title, listOf("sad", "cry", "lonely", "hurt", "heartbreak", "rage", "fight")) -> -16.0
                containsAny(title, listOf("dance", "sun", "summer", "good", "smile", "party", "fun")) -> 18.0
                containsAny(artist, listOf("dayglow", "rex orange county", "boy pablo", "niki", "lany")) -> 12.0
                else -> 8.0
            }

            // REGULATION: When sad, penalize depressing or rap/trap songs and boost comforting/uplifting ones
            "sad" -> when {
                containsAny(title, listOf("rap", "hip hop", "trap", "drill", "freestyle", "party", "dance", "rage", "fight", "hype")) -> -20.0 // Avoid rap/hype
                containsAny(title, listOf("sad", "cry", "heartbreak", "depress")) -> -5.0 // Avoid wallowing
                containsAny(title, listOf("comfort", "better", "sun", "light", "heal", "hope", "love", "smile")) -> 18.0
                containsAny(artist, listOf("clairo", "john mayer", "ben&ben", "ed sheeran", "coldplay")) -> 14.0
                else -> 8.0
            }

            // REGULATION: When angry, penalize aggressive or rap/trap songs and boost soothing ones
            "angry" -> when {
                containsAny(title, listOf("rap", "hip hop", "trap", "drill", "freestyle", "rage", "fight", "brutal", "bad", "misery", "loud", "kill")) -> -22.0 // Avoid rap/hype
                containsAny(title, listOf("peace", "calm", "breathe", "soft", "quiet", "ocean", "slow")) -> 18.0
                containsAny(artist, listOf("laufey", "wave to earth", "daniel caesar", "h.e.r.", "norah jones")) -> 14.0
                else -> 8.0
            }

            "calm" -> when {
                containsAny(title, listOf("rap", "hip hop", "trap", "drill", "freestyle", "rage", "fight", "party", "hype", "brutal")) -> -20.0
                containsAny(title, listOf("soft", "moon", "night", "dream", "quiet", "slow", "acoustic")) -> 18.0
                containsAny(artist, listOf("laufey", "wave to earth", "clairo", "keshi", "arthur nery", "daniel caesar")) -> 14.0
                else -> 8.0
            }

            else -> 5.0
        }
    }

    private fun timeFitScore(
        candidate: SpotifyMoodCandidate,
        timeOfDay: String
    ): Double {
        val title = candidate.name.lowercase(Locale.getDefault())
        val time = timeOfDay.trim().lowercase(Locale.getDefault())

        return when (time) {
            "morning" -> if (containsAny(title, listOf("morning", "sun", "day"))) 6.0 else 2.0
            "afternoon" -> if (containsAny(title, listOf("day", "light", "summer"))) 5.0 else 2.0
            "evening" -> if (containsAny(title, listOf("evening", "night", "moon"))) 6.0 else 2.0
            "night" -> if (containsAny(title, listOf("night", "moon", "dream", "sleep"))) 7.0 else 2.0
            else -> 0.0
        }
    }

    private fun containsAny(text: String, keywords: List<String>): Boolean {
        return keywords.any { keyword ->
            text.contains(keyword, ignoreCase = true)
        }
    }

    private fun isImproved(item: HistoryItem): Boolean {
        if (item.moodBefore.isBlank() || item.moodAfter.isBlank()) return false
        return moodScore(item.moodAfter) > moodScore(item.moodBefore)
    }

    private fun isWorsened(item: HistoryItem): Boolean {
        if (item.moodBefore.isBlank() || item.moodAfter.isBlank()) return false
        return moodScore(item.moodAfter) < moodScore(item.moodBefore)
    }

    private fun moodScore(mood: String): Int {
        return when (SpotifyMoodQueryBuilder.normalizeMood(mood)) {
            "angry" -> 1
            "sad" -> 2
            "calm" -> 3
            "happy" -> 4
            else -> 3
        }
    }

    private fun diversifyByArtist(
        candidates: List<SpotifyMoodCandidate>,
        maxPerArtist: Int = 2
    ): List<SpotifyMoodCandidate> {
        val artistCounts = mutableMapOf<String, Int>()

        return candidates.filter { candidate ->
            val artistKey = candidate.artist.trim().lowercase(Locale.getDefault())
            val currentCount = artistCounts[artistKey] ?: 0

            if (currentCount < maxPerArtist) {
                artistCounts[artistKey] = currentCount + 1
                true
            } else {
                false
            }
        }
    }

    private fun dedupeCandidates(list: List<SpotifyMoodCandidate>): List<SpotifyMoodCandidate> {
        val seen = linkedSetOf<String>()
        val output = mutableListOf<SpotifyMoodCandidate>()

        for (candidate in list.sortedByDescending { it.popularity }) {
            val key = globalSongKey(candidate.name, candidate.artist)

            if (seen.add(key)) {
                output.add(candidate)
            }
        }

        return output
    }

    private fun buildReason(
        candidate: SpotifyMoodCandidate,
        history: List<HistoryItem>,
        mood: String,
        timeOfDay: String
    ): String {
        val normalizedMood = SpotifyMoodQueryBuilder.displayMood(mood)
        val reasons = mutableListOf<String>()

        if (history.any {
                it.recommendedArtist.equals(candidate.artist, ignoreCase = true) &&
                        isImproved(it)
            }) {
            reasons.add("Helped improve your mood before")
        }

        if (history.any {
                it.recommendedSongTitle.equals(candidate.name, ignoreCase = true) &&
                        it.wasReplayed
            }) {
            reasons.add("You replayed this before")
        }

        // REGULATION TEXT UPDATE
        when (SpotifyMoodQueryBuilder.normalizeMood(mood)) {
            "sad" -> reasons.add("To help lift your mood")
            "angry" -> reasons.add("To help calm you down")
            else -> reasons.add("Matches your $normalizedMood mood")
        }

        if (timeOfDay.equals("night", ignoreCase = true)) {
            reasons.add("Fits night listening")
        }

        return reasons.take(2).joinToString(" • ")
    }

    private fun globalSongKey(title: String, artist: String): String {
        return "${title.trim().lowercase(Locale.getDefault())}|${artist.trim().lowercase(Locale.getDefault())}"
    }

    private fun fallbackLocal(mood: String): Result {
        val normalizedMood = SpotifyMoodQueryBuilder.normalizeMood(mood)

        val local = when (normalizedMood) {
            "in_love" -> listOf(
                HomeActivity.Song("Pasilyo", "SunKissed Lola", "4:30", "Matches your romantic mood"),
                HomeActivity.Song("Palagi", "TJ Monterde", "4:15", "Matches your romantic mood"),
                HomeActivity.Song("Until I Found You", "Stephen Sanchez", "2:57", "Matches your romantic mood"),
                HomeActivity.Song("Pagsamo", "Zack Tabudlo", "4:42", "Matches your romantic mood"),
                HomeActivity.Song("I Like Me Better", "Lauv", "3:17", "Matches your romantic mood")
            )

            "hype" -> listOf(
                HomeActivity.Song("Gento", "SB19", "3:52", "Matches your high energy mood"),
                HomeActivity.Song("Pantropiko", "BINI", "3:43", "Matches your high energy mood"),
                HomeActivity.Song("Blinding Lights", "The Weeknd", "3:20", "Matches your high energy mood"),
                HomeActivity.Song("Uptown Funk", "Mark Ronson ft. Bruno Mars", "4:30", "Matches your high energy mood"),
                HomeActivity.Song("Can't Stop the Feeling!", "Justin Timberlake", "3:56", "Matches your high energy mood")
            )

            "hugot" -> listOf(
                HomeActivity.Song("Kathang Isip", "Ben&Ben", "5:18", "Matches your hugot mood"),
                HomeActivity.Song("Kung 'Di Rin Lang Ikaw", "December Avenue", "4:27", "Matches your hugot mood"),
                HomeActivity.Song("Paubaya", "Moira Dela Torre", "4:24", "Matches your hugot mood"),
                HomeActivity.Song("Sa Hindi Pag-Alala", "Munimuni", "5:22", "Matches your hugot mood"),
                HomeActivity.Song("Glimpse of Us", "Joji", "3:53", "Matches your hugot mood")
            )

            "happy" -> listOf(
                HomeActivity.Song("Glue Song", "beabadoobee", "2:15", "Matches your happy mood"),
                HomeActivity.Song("Sunflower", "Rex Orange County", "4:12", "Matches your happy mood"),
                HomeActivity.Song("Dance, Baby!", "boy pablo", "3:18", "Matches your happy mood"),
                HomeActivity.Song("Every Summertime", "NIKI", "3:35", "Matches your happy mood"),
                HomeActivity.Song("Can I Call You Tonight?", "Dayglow", "4:38", "Matches your happy mood")
            )

            "sad" -> listOf(
                HomeActivity.Song("Promise", "Laufey", "3:54", "Matches your sad mood"),
                HomeActivity.Song("As the World Caves In", "Matt Maltese", "3:38", "Matches your sad mood"),
                HomeActivity.Song("Shouldn't Be", "Luke Chiang", "3:13", "Matches your sad mood"),
                HomeActivity.Song("I Bet on Losing Dogs", "Mitski", "2:50", "Matches your sad mood"),
                HomeActivity.Song("The Night We Met", "Lord Huron", "3:28", "Matches your sad mood")
            )

            "angry" -> listOf(
                HomeActivity.Song("good 4 u", "Olivia Rodrigo", "2:58", "Matches your angry mood"),
                HomeActivity.Song("Misery Business", "Paramore", "3:31", "Matches your angry mood"),
                HomeActivity.Song("505", "Arctic Monkeys", "4:13", "Matches your angry mood"),
                HomeActivity.Song("brutal", "Olivia Rodrigo", "2:23", "Matches your angry mood"),
                HomeActivity.Song("Decode", "Paramore", "4:21", "Matches your angry mood")
            )

            "calm" -> listOf(
                HomeActivity.Song("Like the Movies", "Laufey", "3:23", "Matches your calm mood"),
                HomeActivity.Song("Paragraphs", "Luke Chiang", "3:12", "Matches your calm mood"),
                HomeActivity.Song("seasons", "wave to earth", "4:16", "Matches your calm mood"),
                HomeActivity.Song("Softly", "Clairo", "3:06", "Matches your calm mood"),
                HomeActivity.Song("Beside You", "keshi", "2:46", "Matches your calm mood")
            )

            else -> listOf(
                HomeActivity.Song("From The Start", "Laufey", "2:49", "Matches your calm mood"),
                HomeActivity.Song("Bad Habit", "Steve Lacy", "3:52", "Matches your calm mood"),
                HomeActivity.Song("About You", "The 1975", "5:26", "Matches your calm mood"),
                HomeActivity.Song("Best Part", "Daniel Caesar", "3:29", "Matches your calm mood"),
                HomeActivity.Song("lowkey", "NIKI", "2:51", "Matches your calm mood")
            )
        }

        return Result(local.shuffled().take(7), emptyMap())
    }

    private suspend fun getTechnicalRecommendations(
        accessToken: String,
        mood: String,
        limit: Int
    ): List<SpotifyMoodCandidate> {
        val preferredGenres = UserPreferenceManager.getPreferredGenres(context)
        
        // REGULATION LOGIC: Target audio features to actively lift or soothe mood
        val (baseValence, baseEnergy, baseGenres) = when (mood) {
            "in_love" -> Triple(0.85, 0.60, "romance,pop,acoustic")
            "hype" -> Triple(0.80, 0.92, "dance,pop,work-out")
            "hugot" -> Triple(0.20, 0.30, "acoustic,indie,sad")
            "happy" -> Triple(0.92, 0.88, "happy,pop,dance") 
            "sad" -> Triple(0.65, 0.45, "acoustic,pop,feel-good") // REGULATE: Uplifting/Comforting instead of depressing
            "angry" -> Triple(0.60, 0.20, "ambient,chill,acoustic") // REGULATE: Soothing/Calming instead of aggressive
            "calm" -> Triple(0.75, 0.12, "ambient,piano,chill")
            else -> Triple(0.5, 0.5, "indie,pop")
        }

        // Merge user preferences into genre seeds if they exist
        // We limit to 3 genres total to leave room for other seeds if needed
        val finalGenres = if (preferredGenres.isNotEmpty()) {
            val combined = (preferredGenres + baseGenres.split(",")).distinct().take(3)
            combined.joinToString(",")
        } else {
            baseGenres
        }

        return SpotifyMoodSearchRepository.getRecommendationsByFeatures(
            accessToken = accessToken,
            targetValence = baseValence,
            targetEnergy = baseEnergy,
            seedGenres = finalGenres,
            limit = limit
        )
    }
}
