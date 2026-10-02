package com.example.moodsync

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ExperimentalGetImage
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class ForecastActivity : AppCompatActivity() {

    private lateinit var imgBack: ImageView
    private lateinit var imgProfile: ImageView

    private lateinit var tvPredictedMood: TextView
    private lateinit var tvConfidence: TextView
    private lateinit var tvReason: TextView
    private lateinit var tvWeeklyMood: TextView
    private lateinit var tvBestDay: TextView
    private lateinit var tvBestTime: TextView
    private lateinit var tvInsight: TextView
    private lateinit var tvDailySummary: TextView

    private lateinit var tabHome: TextView
    private lateinit var tabHistory: TextView
    private lateinit var tabPlaylist: TextView

    private lateinit var navHome: LinearLayout
    private lateinit var navScan: LinearLayout
    private lateinit var navProfile: LinearLayout

    private lateinit var cardStats: LinearLayout
    private lateinit var btnViewStats: TextView

    private lateinit var recyclerFutureSongs: RecyclerView
    private lateinit var futureSongsAdapter: FutureSongsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forecast)

        NotificationNavHelper.setup(this)

        bindViews()
        setupFutureSongsRecycler()
        setupNavigation()
        loadForecast()
        loadForecastTimeline()
        ProfileImageLoader.load(imgProfile)
    }

    private fun bindViews() {
        imgBack = findViewById(R.id.imgBack)
        imgProfile = findViewById(R.id.imgProfile)

        tvPredictedMood = findViewById(R.id.tvPredictedMood)
        tvConfidence = findViewById(R.id.tvConfidence)
        tvReason = findViewById(R.id.tvReason)
        tvWeeklyMood = findViewById(R.id.tvWeeklyMood)
        tvBestDay = findViewById(R.id.tvBestDay)
        tvBestTime = findViewById(R.id.tvBestTime)
        tvInsight = findViewById(R.id.tvInsight)
        tvDailySummary = findViewById(R.id.tvDailySummary)

        tabHome = findViewById(R.id.tabHome)
        tabHistory = findViewById(R.id.tabHistory)
        tabPlaylist = findViewById(R.id.tabPlaylist)

        navHome = findViewById(R.id.navHome)
        navScan = findViewById(R.id.navScan)
        navProfile = findViewById(R.id.navProfile)

        cardStats = findViewById(R.id.cardStats)
        btnViewStats = findViewById(R.id.btnViewStats)

        recyclerFutureSongs = findViewById(R.id.recyclerFutureSongs)
    }

    private fun setupFutureSongsRecycler() {
        futureSongsAdapter = FutureSongsAdapter { item ->
            val intent = Intent(this, HomeActivity::class.java).apply {
                putExtra("playlist_song_title", item.songTitle)
                putExtra("playlist_song_artist", item.artist)
                putExtra("playlist_song_duration", "0:00")
                putExtra("open_from_playlist", true)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

            startActivity(intent)
        }

        recyclerFutureSongs.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        recyclerFutureSongs.adapter = futureSongsAdapter
    }

    @OptIn(ExperimentalGetImage::class)
    private fun setupNavigation() {
        imgBack.setOnClickListener { finish() }

        imgProfile.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        tabHome.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }

        tabHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
            finish()
        }

        tabPlaylist.setOnClickListener {
            startActivity(Intent(this, PlaylistActivity::class.java))
            finish()
        }

        navHome.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }

        navScan.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
            finish()
        }

        navProfile.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
        }

        cardStats.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }

        btnViewStats.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }
    }

    private fun loadForecast() {
        val history = HistoryStorage.getHistory(this)
            .filter { it.emotion.isNotBlank() }
            .sortedBy { it.epochMillis }

        if (history.isEmpty()) {
            showNoDataState()
            return
        }

        val result = MoodForecastEngine(this).generateForecast()
        
        val predictedMood = result.predictedMood
        val confidence = result.confidence
        val totalEntries = history.size
        
        val weeklyMood = result.weeklyDominantMood
        val bestDay = result.bestDay
        val bestTime = result.bestTimeOfDay
        
        val recentHistory = getRecentEntries(history, 20)
        val nextLikelyMood = predictNextMood(history)
        val stabilityScore = calculateStabilityScore(recentHistory)
        val confidenceLabel = getConfidenceLabel(confidence, totalEntries)
        val songEffect = analyzeBestSongEffect(history)
        val artistEffect = analyzeBestArtistEffect(history)

        val insight = buildMoodInsight(
            history = history,
            recentHistory = recentHistory,
            predictedMood = predictedMood,
            confidence = confidence,
            totalEntries = totalEntries,
            currentDay = getCurrentDayOfWeek(),
            currentTime = getCurrentTimeOfDay(),
            weeklyMood = weeklyMood,
            bestDay = bestDay,
            bestTime = bestTime,
            nextLikelyMood = nextLikelyMood,
            stabilityScore = stabilityScore,
            songEffect = songEffect,
            artistEffect = artistEffect
        )

        tvPredictedMood.text = formatText(predictedMood)
        tvConfidence.text = "Confidence: $confidenceLabel (${(confidence * 100).toInt()}%)"
        tvReason.text = shortMainReason(result.reason)
        tvWeeklyMood.text = formatText(weeklyMood)
        tvBestDay.text = "Best Day: $bestDay"
        tvBestTime.text = "Best Time: $bestTime"
        tvInsight.text = shortInsight(predictedMood, bestDay, bestTime)
    }

    private fun loadForecastTimeline() {
        futureSongsAdapter.submitList(emptyList())

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val timeline = MoodPredictionEngine(this@ForecastActivity).generateTimeline(daysAhead = 3)

                if (timeline.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        futureSongsAdapter.submitList(emptyList())
                    }
                    return@launch
                }

                val futureRecommendations = buildFutureRecommendations(timeline)

                val mergedTimeline = mergeTimelineWithRecommendations(
                    timeline = timeline,
                    recommendations = futureRecommendations
                )

                val todayTimeline = timeline.filter { it.dayLabel == "Today" }
                val summary = buildDailySummary(todayTimeline)

                withContext(Dispatchers.Main) {
                    tvDailySummary.text = summary
                    futureSongsAdapter.submitList(futureRecommendations.take(3))
                }

            } catch (e: Exception) {
                e.printStackTrace()

                withContext(Dispatchers.Main) {
                    futureSongsAdapter.submitList(emptyList())
                }
            }
        }
    }

    private suspend fun buildFutureRecommendations(
        timeline: List<MoodPredictionEngine.ForecastPoint>
    ): List<FutureRecommendationItem> {
        val convertedTimeline = timeline.map { point ->
            com.example.moodsync.ForecastTimelineItem(
                label = "${point.dayLabel} ${point.timeOfDay}",
                predictedMood = point.predictedMood,
                confidence = (point.confidence * 100).toInt(),
                reason = point.reason
            )
        }

        val spotifyRecommendations =
            ForecastSpotifyRecommendationEngine(this)
                .getRecommendationsForTimeline(
                    timeline = convertedTimeline,
                    limitPerPoint = 1
                )

        if (spotifyRecommendations.isNotEmpty()) {
            return spotifyRecommendations
        }

        val songs = MoodSongRepository.getSongs()

        return FutureRecommendationEngine.generateFutureRecommendations(
            context = this,
            timeline = convertedTimeline,
            songs = songs,
            maxPerTimeline = 1
        ).sortedByDescending { it.score }
    }



    private fun mergeTimelineWithRecommendations(
        timeline: List<MoodPredictionEngine.ForecastPoint>,
        recommendations: List<FutureRecommendationItem>
    ): List<MoodPredictionEngine.ForecastPoint> {

        return timeline.map { point ->
            val label = "${point.dayLabel} ${point.timeOfDay}"

            val rec = recommendations.firstOrNull {
                it.timelineLabel.equals(label, ignoreCase = true) &&
                        it.predictedMood.equals(point.predictedMood, ignoreCase = true)
            }

            if (rec != null) {
                point.copy(reason = "Song: ${rec.songTitle}")
            } else {
                point.copy(reason = shortTimelineReason(point.reason))
            }
        }
    }

    private fun shortMainReason(reason: String): String {
        return when {
            reason.contains("strong repeating pattern", ignoreCase = true) ->
                "Based on a strong repeating pattern."
            reason.contains("partial repeating pattern", ignoreCase = true) ->
                "Based on a partial repeating pattern."
            reason.contains("recent mood transitions", ignoreCase = true) ->
                "Based on your recent mood transitions."
            reason.contains("usual", ignoreCase = true) ->
                "Based on your usual mood pattern."
            else ->
                "Based on your recent mood history."
        }
    }

    private fun shortTimelineReason(reason: String): String {
        return when {
            reason.contains("strong", ignoreCase = true) -> "Strong pattern"
            reason.contains("partial", ignoreCase = true) -> "Partial pattern"
            reason.contains("transition", ignoreCase = true) -> "Transitions"
            reason.contains("time", ignoreCase = true) -> "Time pattern"
            reason.contains("day", ignoreCase = true) -> "Day pattern"
            else -> "Recent history"
        }
    }

    private fun shortInsight(
        predictedMood: String,
        bestDay: String,
        bestTime: String
    ): String {
        return if (bestDay.equals("No data", ignoreCase = true) ||
            bestTime.equals("No data", ignoreCase = true)
        ) {
            "You usually lean ${predictedMood.lowercase()} based on recent history."
        } else {
            "You usually lean ${predictedMood.lowercase()} around ${bestTime.lowercase()}, especially on ${bestDay.lowercase()}."
        }
    }

    private fun buildDailySummary(todayTimeline: List<MoodPredictionEngine.ForecastPoint>): String {
        if (todayTimeline.isEmpty()) return "Your daily mood summary will appear here once you have more history."

        val moods = todayTimeline.map { it.predictedMood.lowercase() }.distinct()
        val morning = todayTimeline.find { it.timeOfDay == "Morning" }?.predictedMood?.lowercase()
        val evening = todayTimeline.find { it.timeOfDay == "Evening" }?.predictedMood?.lowercase()
        val night = todayTimeline.find { it.timeOfDay == "Night" }?.predictedMood?.lowercase()

        val mainMood = todayTimeline.groupingBy { it.predictedMood.lowercase() }
            .eachCount().maxByOrNull { it.value }?.key ?: "stable"

        val sb = StringBuilder()
        sb.append("Today looks primarily $mainMood. ")

        if (morning != null && night != null && morning != night) {
            sb.append("You might start your day feeling $morning and transition towards a $night mood by tonight. ")
        } else if (moods.size == 1) {
            sb.append("Your mood is expected to remain consistent throughout the day. ")
        }

        if (evening == "calm" || evening == "relaxed") {
            sb.append("An ideal time for unwinding is expected during the evening. ")
        } else if (evening == "happy" || evening == "energetic") {
            sb.append("Expect a peak in your mood during the afternoon or evening. ")
        }

        return sb.toString().trim()
    }

    private fun showNoDataState() {
        tvPredictedMood.text = "No data yet"
        tvConfidence.text = "Confidence: Building"
        tvReason.text = "eMoodtune needs more history before it can forecast your patterns."
        tvWeeklyMood.text = "No data"
        tvBestDay.text = "Best Day: No data"
        tvBestTime.text = "Best Time: No data"
        tvInsight.text = "Keep scanning regularly so eMoodtune can learn your mood rhythm over time."
        tvDailySummary.text = "Not enough data to generate a daily summary."
        futureSongsAdapter.submitList(emptyList())
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

    private fun buildMoodInsight(
        history: List<HistoryItem>,
        recentHistory: List<HistoryItem>,
        predictedMood: String,
        confidence: Float,
        totalEntries: Int,
        currentDay: String,
        currentTime: String,
        weeklyMood: String,
        bestDay: String,
        bestTime: String,
        nextLikelyMood: String?,
        stabilityScore: Int,
        songEffect: SongEffectResult?,
        artistEffect: ArtistEffectResult?
    ): String {
        val transition = detectMoodTransition(history)
        val recentMood = if (recentHistory.isNotEmpty()) {
            mostFrequentMood(recentHistory)
        } else {
            weeklyMood
        }

        val trendText = buildTrendText(recentHistory)
        val streakText = buildStreakText(recentHistory)
        val stabilityText = buildStabilityText(stabilityScore)
        val nextMoodText = if (!nextLikelyMood.isNullOrBlank()) {
            " After this, your next likely mood is ${nextLikelyMood.lowercase()}."
        } else {
            ""
        }

        val songText = when {
            songEffect != null && artistEffect != null ->
                " Your strongest mood-lifting pattern is ${songEffect.songTitle} by ${artistEffect.artist}, often linked to ${songEffect.fromMood.lowercase()} to ${songEffect.toMood.lowercase()} shifts."
            songEffect != null ->
                " Your strongest mood-lifting pattern is ${songEffect.songTitle}, often linked to ${songEffect.fromMood.lowercase()} to ${songEffect.toMood.lowercase()} shifts."
            artistEffect != null ->
                " Songs by ${artistEffect.artist} are often followed by improved moods."
            else -> ""
        }

        return when {
            totalEntries < 5 ->
                "eMoodtune is still learning your behavior. Right now it leans ${predictedMood.lowercase()} for $currentDay $currentTime. $stabilityText Keep using the app to improve accuracy.$songText"

            confidence < 0.40f ->
                "Your mood pattern for $currentDay $currentTime is still inconsistent. Recently, your mood trend leans ${recentMood.lowercase()}. $trendText $stabilityText$songText"

            confidence < 0.65f -> {
                val transitionText = if (transition != null) {
                    " You often move from ${transition.first} to ${transition.second}."
                } else {
                    ""
                }

                "eMoodtune sees a developing pattern toward ${predictedMood.lowercase()} for $currentDay $currentTime.$transitionText $streakText$nextMoodText$songText"
            }

            else -> {
                val transitionText = if (transition != null) {
                    " You commonly shift from ${transition.first} to ${transition.second}."
                } else {
                    ""
                }

                "eMoodtune sees a strong pattern toward ${predictedMood.lowercase()} for $currentDay $currentTime.$transitionText Your strongest weekly pattern appears on ${bestDay.lowercase()} during the ${bestTime.lowercase()}. $streakText $stabilityText$nextMoodText$songText"
            }
        }.replace("\\s+".toRegex(), " ").trim()
    }

    private fun buildTrendText(recentHistory: List<HistoryItem>): String {
        if (recentHistory.size < 3) {
            return "There is not enough recent data yet to detect a stronger trend."
        }

        val last3 = getRecentEntries(recentHistory, 3)
        val moods = last3.map { it.emotion.trim().lowercase() }

        return if (moods.distinct().size == 1) {
            "Your last ${last3.size} entries were consistently ${moods.first()}."
        } else {
            "Your recent entries are still varied, so eMoodtune is keeping the forecast flexible."
        }
    }

    private fun buildStreakText(recentHistory: List<HistoryItem>): String {
        val streak = getCurrentMoodStreak(recentHistory)
        return if (streak.count >= 3) {
            "You are currently on a ${streak.count}-entry ${streak.mood.lowercase()} streak."
        } else {
            "There is no strong recent streak yet."
        }
    }

    private fun buildStabilityText(stabilityScore: Int): String {
        return when {
            stabilityScore >= 80 -> "Your recent mood pattern looks very stable."
            stabilityScore >= 60 -> "Your recent mood pattern looks fairly stable."
            stabilityScore >= 40 -> "Your recent mood pattern is moderately mixed."
            else -> "Your recent mood pattern is quite variable right now."
        }
    }

    private fun getConfidenceLabel(confidence: Float, totalEntries: Int): String {
        return when {
            totalEntries < 5 -> "Building"
            confidence < 0.35f -> "Low"
            confidence < 0.60f -> "Moderate"
            confidence < 0.80f -> "High"
            else -> "Very High"
        }
    }

    private fun detectMoodTransition(history: List<HistoryItem>): Pair<String, String>? {
        if (history.size < 2) return null
        val transitions = mutableMapOf<Pair<String, String>, Int>()
        for (i in 0 until history.size - 1) {
            val current = history[i].emotion.trim().lowercase()
            val next = history[i + 1].emotion.trim().lowercase()
            if (current.isBlank() || next.isBlank()) continue
            val pair = Pair(current, next)
            transitions[pair] = (transitions[pair] ?: 0) + 1
        }
        return transitions.maxByOrNull { it.value }?.key
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

    private fun calculateStabilityScore(recentHistory: List<HistoryItem>): Int {
        if (recentHistory.isEmpty()) return 0
        val moods = recentHistory.map { it.emotion.trim().lowercase() }.filter { it.isNotBlank() }
        if (moods.isEmpty()) return 0
        val mostCommonCount = moods.groupingBy { it }.eachCount().maxByOrNull { it.value }?.value ?: 0
        return ((mostCommonCount.toFloat() / moods.size.toFloat()) * 100f).toInt().coerceIn(0, 100)
    }

    private fun getCurrentMoodStreak(recentHistory: List<HistoryItem>): MoodStreak {
        if (recentHistory.isEmpty()) return MoodStreak("Unknown", 0)
        val lastMood = recentHistory.last().emotion.trim()
        var count = 0
        for (i in recentHistory.indices.reversed()) {
            if (recentHistory[i].emotion.equals(lastMood, ignoreCase = true)) {
                count++
            } else {
                break
            }
        }
        return MoodStreak(lastMood.ifBlank { "Unknown" }, count)
    }

    private fun analyzeBestSongEffect(history: List<HistoryItem>): SongEffectResult? {
        if (history.size < 2) return null

        val effects = mutableMapOf<String, SongEffectAccumulator>()

        for (i in 0 until history.size - 1) {
            val current = history[i]
            val next = history[i + 1]

            val songTitle = current.songTitle.trim()
            if (songTitle.isBlank()) continue

            val effect = compareMoodEffect(current.emotion, next.emotion)

            val existing = effects[songTitle]
            if (existing == null) {
                effects[songTitle] = SongEffectAccumulator(
                    songTitle = songTitle,
                    artist = current.artist.trim(),
                    fromMood = current.emotion.trim(),
                    toMood = next.emotion.trim(),
                    improvedCount = if (effect == MoodEffect.IMPROVED) 1 else 0,
                    totalCount = 1
                )
            } else {
                val improved = existing.improvedCount + if (effect == MoodEffect.IMPROVED) 1 else 0
                effects[songTitle] = existing.copy(
                    improvedCount = improved,
                    totalCount = existing.totalCount + 1
                )
            }
        }

        val best = effects.values
            .filter { it.totalCount >= 1 && it.improvedCount > 0 }
            .maxByOrNull { it.improvedCount.toDouble() / it.totalCount.toDouble() + (it.improvedCount * 0.1) }
            ?: return null

        return SongEffectResult(
            songTitle = best.songTitle,
            artist = best.artist,
            fromMood = best.fromMood.ifBlank { "mixed mood" },
            toMood = best.toMood.ifBlank { "better mood" },
            improvedCount = best.improvedCount,
            totalCount = best.totalCount
        )
    }

    private fun analyzeBestArtistEffect(history: List<HistoryItem>): ArtistEffectResult? {
        if (history.size < 2) return null

        val effects = mutableMapOf<String, ArtistEffectAccumulator>()

        for (i in 0 until history.size - 1) {
            val current = history[i]
            val next = history[i + 1]

            val artist = current.artist.trim()
            if (artist.isBlank()) continue

            val effect = compareMoodEffect(current.emotion, next.emotion)

            val existing = effects[artist]
            if (existing == null) {
                effects[artist] = ArtistEffectAccumulator(
                    artist = artist,
                    improvedCount = if (effect == MoodEffect.IMPROVED) 1 else 0,
                    totalCount = 1
                )
            } else {
                val improved = existing.improvedCount + if (effect == MoodEffect.IMPROVED) 1 else 0
                effects[artist] = existing.copy(
                    improvedCount = improved,
                    totalCount = existing.totalCount + 1
                )
            }
        }

        val best = effects.values
            .filter { it.totalCount >= 1 && it.improvedCount > 0 }
            .maxByOrNull { it.improvedCount.toDouble() / it.totalCount.toDouble() + (it.improvedCount * 0.1) }
            ?: return null

        return ArtistEffectResult(
            artist = best.artist,
            improvedCount = best.improvedCount,
            totalCount = best.totalCount
        )
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

    private fun formatText(text: String): String {
        return text.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
    }

    override fun onResume() {
        super.onResume()

        try {
            loadForecast()
            loadForecastTimeline()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    data class MoodStreak(
        val mood: String,
        val count: Int
    )

    data class SongEffectResult(
        val songTitle: String,
        val artist: String,
        val fromMood: String,
        val toMood: String,
        val improvedCount: Int,
        val totalCount: Int
    )

    data class ArtistEffectResult(
        val artist: String,
        val improvedCount: Int,
        val totalCount: Int
    )

    data class SongEffectAccumulator(
        val songTitle: String,
        val artist: String,
        val fromMood: String,
        val toMood: String,
        val improvedCount: Int,
        val totalCount: Int
    )

    data class ArtistEffectAccumulator(
        val artist: String,
        val improvedCount: Int,
        val totalCount: Int
    )

    enum class MoodEffect {
        IMPROVED,
        UNCHANGED,
        WORSENED
    }
}