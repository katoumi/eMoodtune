package com.example.moodsync

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class StatsActivity : AppCompatActivity() {

    private lateinit var imgProfile: ImageView

    private lateinit var tvImprovementRate: TextView
    private lateinit var tvMostReplayedValue: TextView

    private lateinit var tvOverviewValue: TextView
    private lateinit var tvTrendValue: TextView
    private lateinit var tvTotalEntries: TextView
    private lateinit var tvSummary: TextView
    private lateinit var tvEmptyState: TextView

    private lateinit var recyclerMoodStats: RecyclerView
    private lateinit var recyclerWeekdayStats: RecyclerView
    private lateinit var recyclerTimeStats: RecyclerView

    private lateinit var cardMoodStats: View
    private lateinit var cardWeekdayStats: View
    private lateinit var cardTimeStats: View
    private lateinit var cardFeedbackStats: View

    private lateinit var tvBestSongValue: TextView
    private lateinit var tvBestArtistValue: TextView
    private lateinit var tvBestRecoveryValue: TextView
    private lateinit var tvMostSkippedValue: TextView

    private lateinit var tabHome: TextView
    private lateinit var tabHistory: TextView
    private lateinit var tabPlaylist: TextView

    private lateinit var navHome: LinearLayout
    private lateinit var navScan: LinearLayout
    private lateinit var navProfile: LinearLayout

    private lateinit var moodAdapter: StatsBarAdapter
    private lateinit var weekdayAdapter: StatsBarAdapter
    private lateinit var timeAdapter: StatsBarAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        NotificationNavHelper.setup(this)

        bindViews()
        setupRecyclerViews()
        setupNavigation()
    }

    override fun onResume() {
        super.onResume()
        loadStats()
        ProfileImageLoader.load(imgProfile)
    }

    private fun bindViews() {
        imgProfile = findViewById(R.id.imgProfile)

        tvOverviewValue = findViewById(R.id.tvOverviewValue)
        tvTrendValue = findViewById(R.id.tvTrendValue)
        tvTotalEntries = findViewById(R.id.tvTotalEntries)
        tvSummary = findViewById(R.id.tvSummary)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        tvImprovementRate = findViewById(R.id.tvImprovementRate)
        tvMostReplayedValue = findViewById(R.id.tvMostReplayedValue)

        recyclerMoodStats = findViewById(R.id.recyclerMoodStats)
        recyclerWeekdayStats = findViewById(R.id.recyclerWeekdayStats)
        recyclerTimeStats = findViewById(R.id.recyclerTimeStats)

        cardMoodStats = findViewById(R.id.cardMoodStats)
        cardWeekdayStats = findViewById(R.id.cardWeekdayStats)
        cardTimeStats = findViewById(R.id.cardTimeStats)
        cardFeedbackStats = findViewById(R.id.cardFeedbackStats)

        tvBestSongValue = findViewById(R.id.tvBestSongValue)
        tvBestArtistValue = findViewById(R.id.tvBestArtistValue)
        tvBestRecoveryValue = findViewById(R.id.tvBestRecoveryValue)
        tvMostSkippedValue = findViewById(R.id.tvMostSkippedValue)

        tabHome = findViewById(R.id.tabHome)
        tabHistory = findViewById(R.id.tabHistory)
        tabPlaylist = findViewById(R.id.tabPlaylist)

        navHome = findViewById(R.id.navHome)
        navScan = findViewById(R.id.navScan)
        navProfile = findViewById(R.id.navProfile)
    }

    private fun setupRecyclerViews() {
        moodAdapter = StatsBarAdapter()
        weekdayAdapter = StatsBarAdapter()
        timeAdapter = StatsBarAdapter()

        recyclerMoodStats.layoutManager = LinearLayoutManager(this)
        recyclerMoodStats.adapter = moodAdapter
        recyclerMoodStats.isNestedScrollingEnabled = false

        recyclerWeekdayStats.layoutManager = LinearLayoutManager(this)
        recyclerWeekdayStats.adapter = weekdayAdapter
        recyclerWeekdayStats.isNestedScrollingEnabled = false

        recyclerTimeStats.layoutManager = LinearLayoutManager(this)
        recyclerTimeStats.adapter = timeAdapter
        recyclerTimeStats.isNestedScrollingEnabled = false
    }

    private fun setupNavigation() {
        imgProfile.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
        }

        tabHome.setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }

        tabHistory.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }

        tabPlaylist.setOnClickListener {
            val intent = Intent(this, PlaylistActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }

        navHome.setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }

        navScan.setOnClickListener {
            val intent = Intent(this, ScanActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }

        navProfile.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }
    }

    private fun loadStats() {
        val history = HistoryStorage.getHistory(this)
            .filter { it.emotion.isNotBlank() }
            .sortedBy { it.epochMillis }

        if (history.isEmpty()) {
            showEmptyState()
            return
        }

        val moodStats = buildMoodStats(history)
        val weekdayStats = buildWeekdayStats(history)
        val timeStats = buildTimeStats(history)

        moodAdapter.submitList(moodStats)
        weekdayAdapter.submitList(weekdayStats)
        timeAdapter.submitList(timeStats)

        val dominantMood = moodStats.maxByOrNull { it.value }?.label ?: "No data"
        val recentTrendMood = history.takeLast(7)
            .groupingBy { it.emotion.trim().lowercase() }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?.replaceFirstChar { it.uppercase() }
            ?: dominantMood

        val bestDay = weekdayStats.maxByOrNull { it.value }?.label ?: "No data"
        val bestTime = timeStats.maxByOrNull { it.value }?.label ?: "No data"

        val feedbackSummary = buildFeedbackSummary(history)

        tvOverviewValue.text = dominantMood
        tvTrendValue.text = recentTrendMood
        tvTotalEntries.text = history.size.toString()

        tvSummary.text =
            "Your dominant mood is $dominantMood, while your recent trend leans $recentTrendMood. " +
                    "Most of your mood activity appears on $bestDay during the $bestTime. " +
                    feedbackSummary.summaryText

        tvBestSongValue.text = feedbackSummary.bestSong ?: "No data"
        tvBestArtistValue.text = feedbackSummary.bestArtist ?: "No data"
        tvBestRecoveryValue.text = feedbackSummary.bestRecovery ?: "No data"
        tvMostSkippedValue.text = feedbackSummary.mostSkippedSong ?: "No data"
        tvImprovementRate.text = "${feedbackSummary.improvementRate}%"
        tvMostReplayedValue.text = feedbackSummary.mostReplayedSong ?: "No data"

        tvEmptyState.visibility = View.GONE
        cardMoodStats.visibility = View.VISIBLE
        cardWeekdayStats.visibility = View.VISIBLE
        cardTimeStats.visibility = View.VISIBLE
        cardFeedbackStats.visibility = if (feedbackSummary.hasFeedbackData) View.VISIBLE else View.GONE
    }

    private fun showEmptyState() {
        tvOverviewValue.text = "No data"
        tvTrendValue.text = "No data"
        tvTotalEntries.text = "0"
        tvSummary.text = "Use Scan more often so eMoodtune can build your statistics."

        tvEmptyState.visibility = View.VISIBLE
        cardMoodStats.visibility = View.GONE
        cardWeekdayStats.visibility = View.GONE
        cardTimeStats.visibility = View.GONE
        cardFeedbackStats.visibility = View.GONE
    }

    private fun buildMoodStats(history: List<HistoryItem>): List<StatsBarItem> {
        val order = listOf("Happy", "Calm", "Sad", "Angry")
        val counts = linkedMapOf(
            "Happy" to 0,
            "Calm" to 0,
            "Sad" to 0,
            "Angry" to 0
        )

        history.forEach { item ->
            val mood = item.emotion.trim().replaceFirstChar { it.uppercase() }
            if (counts.containsKey(mood)) {
                counts[mood] = (counts[mood] ?: 0) + 1
            }
        }

        val maxValue = counts.values.maxOrNull()?.coerceAtLeast(1) ?: 1

        return order.map { label ->
            StatsBarItem(
                label = label,
                value = counts[label] ?: 0,
                maxValue = maxValue
            )
        }
    }

    private fun buildWeekdayStats(history: List<HistoryItem>): List<StatsBarItem> {
        val order = listOf(
            "Monday", "Tuesday", "Wednesday", "Thursday",
            "Friday", "Saturday", "Sunday"
        )

        val counts = order.associateWith { 0 }.toMutableMap()

        history.forEach { item ->
            val day = item.dayOfWeek.trim()
            if (counts.containsKey(day)) {
                counts[day] = (counts[day] ?: 0) + 1
            }
        }

        val maxValue = counts.values.maxOrNull()?.coerceAtLeast(1) ?: 1

        return order.map { label ->
            StatsBarItem(
                label = label,
                value = counts[label] ?: 0,
                maxValue = maxValue
            )
        }
    }

    private fun buildTimeStats(history: List<HistoryItem>): List<StatsBarItem> {
        val order = listOf("Morning", "Afternoon", "Evening", "Night")
        val counts = order.associateWith { 0 }.toMutableMap()

        history.forEach { item ->
            val time = item.timeOfDay.trim()
            if (counts.containsKey(time)) {
                counts[time] = (counts[time] ?: 0) + 1
            }
        }

        val maxValue = counts.values.maxOrNull()?.coerceAtLeast(1) ?: 1

        return order.map { label ->
            StatsBarItem(
                label = label,
                value = counts[label] ?: 0,
                maxValue = maxValue
            )
        }
    }

    private fun buildFeedbackSummary(history: List<HistoryItem>): FeedbackSummary {
        if (history.isEmpty()) {
            return FeedbackSummary(
                hasFeedbackData = false,
                summaryText = "eMoodtune is still collecting listening data."
            )
        }

        val songImprovementMap = mutableMapOf<String, Int>()
        val artistImprovementMap = mutableMapOf<String, Int>()
        val transitionMap = mutableMapOf<String, Int>()
        
        val songPlayFrequency = mutableMapOf<String, Int>()
        val songSkipMap = mutableMapOf<String, Int>()
        val songReplayMap = mutableMapOf<String, Int>()

        var totalSessionsWithFeedback = 0
        var improvedSessions = 0

        history.forEach { item ->
            val song = item.songTitle.trim()
            val artist = item.artist.trim()
            
            if (song.isNotBlank() && item.wasPlayed) {
                songPlayFrequency[song] = (songPlayFrequency[song] ?: 0) + 1
            }

            if (item.wasSkipped && song.isNotBlank()) {
                songSkipMap[song] = (songSkipMap[song] ?: 0) + 1
            }

            if (item.wasReplayed && song.isNotBlank()) {
                songReplayMap[song] = (songReplayMap[song] ?: 0) + 1
            }

            if (item.moodBefore.isNotBlank() && item.moodAfter.isNotBlank()) {
                totalSessionsWithFeedback++
                val effect = compareMoodEffect(item.moodBefore, item.moodAfter)

                if (effect == MoodEffect.IMPROVED) {
                    improvedSessions++
                    if (song.isNotBlank()) {
                        songImprovementMap[song] = (songImprovementMap[song] ?: 0) + 1
                    }
                    if (artist.isNotBlank()) {
                        artistImprovementMap[artist] = (artistImprovementMap[artist] ?: 0) + 1
                    }
                    val transition = "${formatMood(item.moodBefore)} → ${formatMood(item.moodAfter)}"
                    transitionMap[transition] = (transitionMap[transition] ?: 0) + 1
                }
            }
        }

        val improvementRate = if (totalSessionsWithFeedback > 0)
            ((improvedSessions.toFloat() / totalSessionsWithFeedback) * 100).toInt()
        else 0

        // FALLBACK LOGIC: If no improvement data, use general play frequency
        val bestSong = songImprovementMap.maxByOrNull { it.value }?.key 
            ?: songPlayFrequency.filter { it.value > 1 }.maxByOrNull { it.value }?.key

        val bestArtist = artistImprovementMap.maxByOrNull { it.value }?.key
            ?: history.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }?.key

        val bestRecovery = transitionMap.maxByOrNull { it.value }?.key
        val mostSkipped = songSkipMap.maxByOrNull { it.value }?.key
        val mostReplayed = songReplayMap.maxByOrNull { it.value }?.key 
            ?: songPlayFrequency.filter { it.value > 2 }.maxByOrNull { it.value }?.key

        val summaryText = if (totalSessionsWithFeedback > 0) {
            "You improved your mood in $improvementRate% of sessions. Top recovery song: ${bestSong ?: "N/A"}."
        } else {
            "Listen for 60s and share how you feel to help eMoodtune analyze your improvement rate."
        }

        return FeedbackSummary(
            hasFeedbackData = true, // We have general data now due to fallbacks
            bestSong = bestSong ?: "Need more data",
            bestArtist = bestArtist ?: "Need more data",
            bestRecovery = bestRecovery ?: "Provide mood feedback",
            mostSkippedSong = mostSkipped ?: "Keep listening",
            mostReplayedSong = mostReplayed ?: "No repeats yet",
            improvementRate = improvementRate,
            summaryText = summaryText
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

    private fun formatMood(mood: String): String {
        return mood.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
    }

    data class FeedbackSummary(
        val hasFeedbackData: Boolean,
        val bestSong: String? = null,
        val bestArtist: String? = null,
        val bestRecovery: String? = null,
        val mostSkippedSong: String? = null,
        val mostReplayedSong: String? = null,
        val improvementRate: Int = 0,
        val summaryText: String
    )

    enum class MoodEffect {
        IMPROVED,
        UNCHANGED,
        WORSENED
    }
}

data class StatsBarItem(
    val label: String,
    val value: Int,
    val maxValue: Int
)