package com.example.moodsync

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.spotify.protocol.types.PlayerState
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

import androidx.lifecycle.ViewModelProvider
import com.example.moodsync.databinding.ActivityHomeBinding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@androidx.camera.core.ExperimentalGetImage
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by lazy { ViewModelProvider(this)[HomeViewModel::class.java] }

    data class Song(
        val title: String,
        val artist: String,
        val duration: String,
        val reason: String = ""
    )

    private lateinit var songAdapter: RecommendedSongAdapter
    private lateinit var spotifyPlayerController: SpotifyPlayerController
    private var lastSavedTime: Long = 0

    private var hasSavedHistoryForCurrentSession = false
    private var sessionStartTime: Long = 0L
    private var hasAskedMoodAfter: Boolean = false
    private var currentSearchMood: String = ""

    private var emotion: String = "calm"
    private var finalMood: String = "calm"

    private var currentSongs: List<Song> = emptyList()
    private var originalRecommendedSongs: List<Song> = emptyList()
    private var selectedSongIndex: Int = 0

    private var isSearchMode: Boolean = false
    private var lastSearchQuery: String = ""
    private var isLoadingRemoteRecommendations: Boolean = false

    private var currentRecommendationSessionId: String = ""
    private var currentRecommendedSongTitle: String = ""
    private var currentRecommendedArtist: String = ""
    private var hasMarkedCurrentSessionPlayed: Boolean = false

    private var currentSpotifyTrack: SpotifyTrack? = null
    private var pendingSpotifyOpen: Boolean = false
    private var pendingSpotifyUri: String = ""
    private var pendingSpotifySong: Song? = null

    private var spotifyDisplayName: String = ""
    private var spotifyIsPlaying: Boolean = false
    private var spotifyCurrentUri: String = ""
    private var isConnectingSpotifyRemote: Boolean = false
    private var isUserSeeking: Boolean = false
    private var hasAutoplayedThisSession: Boolean = false
    private var isTransitioningTrack: Boolean = false
    private var requestedUri: String = ""
    private var transitionStartTime: Long = 0L

    private var lastSongClickTime: Long = 0L
    private var lastLoggedSpotifyUri: String = ""
    private var lastHistorySavedAtMs: Long = 0L
    private var lastRemotePositionMs: Long = 0L
    private var lastRemoteStateElapsedMs: Long = 0L

    private val progressHandler = Handler(Looper.getMainLooper())
    private val searchHandler = Handler(Looper.getMainLooper())
    private val syncHandler = Handler(Looper.getMainLooper())
    private val transitionHandler = Handler(Looper.getMainLooper())

    private lateinit var audioManager: AudioManager
    private var lastVolume: Int = 0
    private var isMuted: Boolean = false
    private var lastAutoAdvanceTime: Long = 0L

    private val PREF_HOME_STATE = "moodsync_home_state"
    private val KEY_LAST_EMOTION = "last_emotion"
    private val KEY_LAST_FINAL_MOOD = "last_final_mood"
    private val KEY_LAST_LOGGED_URI = "last_logged_uri"

    private var searchRunnable: Runnable? = null

    private var currentProgressSeconds = 0
    private var currentSongDurationSeconds = 0

    private var albumPulseAnimator: AnimatorSet? = null
    private var spotifyTrackCache: MutableMap<String, SpotifyTrack> = mutableMapOf()

    private val transitionNudgeRunnable = Runnable {
        if (isTransitioningTrack) {
            Log.d("PLAYER_SYNC", "Transition slow - nudging Spotify engine")
            SpotifyWakeUpHelper.aggressiveWakeUp(this)
        }
    }

    private val transitionTimeoutRunnable = Runnable {
        if (isTransitioningTrack) {
            Log.d("PLAYER_SYNC", "Transition timed out, resetting UI")
            isTransitioningTrack = false
            requestedUri = ""
            
            // Revert to last known track or idle
            val lastTrack = NowPlayingState.currentTrack
            if (lastTrack != null) {
                binding.tvSongTitle.text = lastTrack.name
                binding.tvArtist.text = lastTrack.artist
                binding.tvStickySongTitle.text = lastTrack.name
                binding.tvStickyArtist.text = lastTrack.artist
            } else {
                binding.tvSongTitle.text = "Ready to play"
                binding.tvArtist.text = "eMoodtune Bridge"
                binding.tvStickySongTitle.text = "Ready to play"
                binding.tvStickyArtist.text = "eMoodtune Bridge"
            }
            
            Toast.makeText(this, "Spotify is taking too long to respond", Toast.LENGTH_SHORT).show()
        }
    }

    private val spotifyProgressRunnable = object : Runnable {
        override fun run() {
            if (spotifyPlayerController.isConnected() && spotifyIsPlaying && !isUserSeeking) {
                val elapsedMs = SystemClock.elapsedRealtime() - lastRemoteStateElapsedMs
                val estimatedMs = (lastRemotePositionMs + elapsedMs)
                    .coerceAtLeast(0L)
                    .coerceAtMost(currentSongDurationSeconds * 1000L)

                currentProgressSeconds = (estimatedMs / 1000L).toInt()
                    .coerceIn(0, currentSongDurationSeconds)

                updateSeekUI()
                maybeAskMoodAfter()
            }

            progressHandler.postDelayed(this, 500)
        }
    }

    private val syncMetadataRunnable = object : Runnable {
        override fun run() {
            if (spotifyPlayerController.isConnected()) {
                spotifyPlayerController.getPlayerState { state ->
                    if (state.track != null) {
                        Log.d("PLAYER_SYNC", "Sync Loop: Metadata found!")
                        syncHandler.removeCallbacks(this)
                    } else {
                        Log.d("PLAYER_SYNC", "Sync Loop: Metadata still empty, retrying...")
                        syncHandler.postDelayed(this, 2000)
                    }
                }
            } else {
                syncHandler.removeCallbacks(this)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MoodRecommendationCache.init(this)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NotificationNavHelper.setup(this)

        setupObservers()
        setupMoodAfterButtons()
        setupSpotifyController()
        setupProfileButton()
        setupTopTabs()
        setupBottomNav()
        setupCenterScanButton()
        setupRecycler()
        setupSearchBar()
        setupPlayerButtons()
        setupVolumeControls()
        setupForecastPreview()
        setupStickyPlayer()
        setupSaveCurrentTrackButtons()
        setupRefreshButton()
        setupDailyMoodPrompt()

        readMoodFromIntent(intent)
        
        // DEBOUNCED PRELOADING: Wait 2.5s before starting heavy background tasks
        // This ensures the main UI and bridge connection have priority on the network
        searchHandler.postDelayed({
            if (!isDestroyed && !isFinishing) {
                preloadImportantMoodsOnly()
            }
        }, 2500)

        // Seamless Spotify Bridge Initialization
        SpotifyWakeUpHelper.silentWakeUp(this)
        
        lifecycleScope.launch(Dispatchers.IO) {
            // Proactively refresh token in background
            SpotifySessionManager.getValidAccessToken(this@HomeActivity)
        }

        // Start bridge service with high-speed handshake logic
        val serviceIntent = Intent(this, SpotifyBackgroundService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)

        updatePreviousSessionMoodAfterIfNeeded()
        loadEmotionSongs()
        handlePlaylistOpenIfNeeded()
        loadForecastPreviewText()
        updateSpotifyStatus()
        updatePlayerModeUI()
        loadSpotifyProfile()
        ProfileImageLoader.load(binding.imgProfile)

        // MANUAL RECONNECT: Allow tapping the status to force a hard bridge reset
        binding.tvSpotifyStatus.setOnClickListener {
            if (!isConnectingSpotifyRemote) {
                Toast.makeText(this, "Re-establishing Spotify Link...", Toast.LENGTH_SHORT).show()
                SpotifyRemoteManager.hardReset(this) {
                    runOnUiThread { Toast.makeText(this, "Spotify Link Restored", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun checkDailyMoodPromptVisibility() {
        val prefs = getSharedPreferences(PREF_HOME_STATE, MODE_PRIVATE)
        val lastPromptTime = prefs.getLong("last_mood_prompt_time", 0L)
        val now = System.currentTimeMillis()
        val thirtyMinutes = 30 * 60 * 1000L // Increased frequency: prompt every 30 minutes on return/open

        if (now - lastPromptTime > thirtyMinutes) {
            binding.cardDailyMoodPrompt.visibility = View.VISIBLE
        } else {
            binding.cardDailyMoodPrompt.visibility = View.GONE
        }
    }

    private fun setupDailyMoodPrompt() {
        checkDailyMoodPromptVisibility()

        binding.btnCloseMoodPrompt.setOnClickListener {
            binding.cardDailyMoodPrompt.visibility = View.GONE
            getSharedPreferences(PREF_HOME_STATE, MODE_PRIVATE)
                .edit()
                .putLong("last_mood_prompt_time", System.currentTimeMillis())
                .apply()
        }

        val onEmojiClick: (String, String) -> Unit = { moodKey, moodLabel ->
            binding.cardDailyMoodPrompt.visibility = View.GONE
            getSharedPreferences(PREF_HOME_STATE, MODE_PRIVATE)
                .edit()
                .putLong("last_mood_prompt_time", System.currentTimeMillis())
                .apply()

            viewModel.performSearch(moodKey)
            Toast.makeText(this, "Feeling $moodLabel! Updating recommendations...", Toast.LENGTH_SHORT).show()
        }

        binding.chipEmojiHappy.setOnClickListener { onEmojiClick("happy", "Happy") }
        binding.chipEmojiInLove.setOnClickListener { onEmojiClick("in_love", "In Love") }
        binding.chipEmojiSad.setOnClickListener { onEmojiClick("sad", "Sad") }
        binding.chipEmojiAngry.setOnClickListener { onEmojiClick("angry", "Angry") }
        binding.chipEmojiCalm.setOnClickListener { onEmojiClick("calm", "Calm") }
        binding.chipEmojiHype.setOnClickListener { onEmojiClick("hype", "Hype") }
        binding.chipEmojiHugot.setOnClickListener { onEmojiClick("hugot", "Hugot") }
    }

    private fun setupObservers() {
        viewModel.recommendationBundle.observe(this) { bundle ->
            currentSongs = bundle.songs
            
            // IMMEDIATE BINDING: Inject Spotify URIs into cache before user clicks
            rebuildSpotifyTrackCache(bundle.songs, bundle.trackCache, clearExisting = false)

            // Preserve the original set ONLY if it's currently empty (first load of mood)
            if (originalRecommendedSongs.isEmpty() && bundle.songs.isNotEmpty()) {
                originalRecommendedSongs = bundle.songs
            }

            if (bundle.songs.isNotEmpty()) {
                syncHighlightWithCurrentTrack()
                songAdapter.updateSongs(bundle.songs, selectedSongIndex)

                // AUTO-PLAY: If bridge is ALREADY connected, check if we should autoplay
                if (!hasAutoplayedThisSession && spotifyPlayerController.isConnected()) {
                    spotifyPlayerController.getPlayerState { state ->
                        if (!hasAutoplayedThisSession && state.track == null) {
                            hasAutoplayedThisSession = true
                            runOnUiThread { selectSong(0) }
                        }
                    }
                }
            }
        }

        viewModel.isLoading.observe(this) { isLoading ->
            if (isLoading) {
                binding.shimmerViewContainer.visibility = View.VISIBLE
                binding.shimmerViewContainer.startShimmer()
                binding.recyclerRecommendations.visibility = View.GONE
            } else {
                binding.shimmerViewContainer.stopShimmer()
                binding.shimmerViewContainer.visibility = View.GONE
                binding.recyclerRecommendations.visibility = View.VISIBLE
            }
        }

        viewModel.moodLabel.observe(this) { label ->
            binding.tvMoodLabel.text = label
        }

        viewModel.currentMood.observe(this) { mood ->
            finalMood = mood
            emotion = mood
            applyEmotionTheme(mood)

            // Persist the mood override globally so it stays active across tabs/restarts
            val isManual = viewModel.isSearchMode.value ?: isSearchMode
            getSharedPreferences(PREF_HOME_STATE, MODE_PRIVATE).edit()
                .putString(KEY_LAST_EMOTION, mood)
                .putString(KEY_LAST_FINAL_MOOD, mood)
                .putBoolean("is_manual_mood", isManual)
                .apply()

            if (isManual) {
                currentSearchMood = mood
                binding.tvMoodLabel.text = "Manual Mood: ${formatMood(mood)}"
            }
        }

        viewModel.isSearchMode.observe(this) { isSearch ->
            isSearchMode = isSearch
            currentSearchMood = if (isSearch) finalMood else ""
        }

        QueueManager.queue.observe(this) { list ->
            // Refresh recommendation list to show/hide "Queued" badges
            if (currentSongs.isNotEmpty()) {
                songAdapter.updateSongs(currentSongs, selectedSongIndex)
            }

            // Update Tab Label with count
            val queueSize = list.size
            binding.tabQueue.text = if (queueSize > 0) "Queue ($queueSize)" else "Queue"

            // Update Up Next Hint
            if (list.isNotEmpty()) {
                binding.tvUpNextHint.visibility = View.VISIBLE
                binding.tvUpNextHint.text = "Up Next: ${list[0].title}"
            } else {
                binding.tvUpNextHint.visibility = View.GONE
            }
        }

        viewModel.playlists.observe(this) {
            // Ensuring playlist metadata is cached for the "Add to Playlist" dialog
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val oldMood = finalMood
        readMoodFromIntent(intent)

        if (oldMood != finalMood) {
            hasAutoplayedThisSession = false
            spotifyTrackCache.clear()
            currentSongs = emptyList()
            originalRecommendedSongs = emptyList()
            selectedSongIndex = 0
            songAdapter.updateSongs(emptyList(), 0)
            loadEmotionSongs()
        }

        handlePlaylistOpenIfNeeded()
    }

    override fun onResume() {
        super.onResume()

        checkDailyMoodPromptVisibility()

        // Reset prompt timer when re-entering the app to prevent instant popups
        sessionStartTime = System.currentTimeMillis()
        hasAskedMoodAfter = false
        binding.moodAfterContainer.visibility = View.GONE

        loadForecastPreviewText()
        loadSpotifyProfile()

        // PROACTIVE RECOVERY: If bridge was lost in background, force a hard handshake
        if (!SpotifyRemoteManager.isConnected()) {
            Log.d("PLAYER_SYNC", "Bridge lost in background - initiating recovery")
            connectSpotifyRemote()
        } else {
            // VERIFY BRIDGE: Sometimes bridge reports 'connected' but is unresponsive (stale)
            spotifyPlayerController.getPlayerState { state ->
                Log.d("PLAYER_SYNC", "Bridge health check: Responsive")
                runOnUiThread { applySpotifyPlayerState(state) }
            }
        }

        if (spotifyPlayerController.isConnected()) {
            spotifyPlayerController.subscribeToPlayerState()
        }

        if (NowPlayingState.currentTrack != null) {
            restoreNowPlayingUI()
        }

        if (originalRecommendedSongs.isEmpty()) {
            loadEmotionSongs()
        } else if (currentSongs.isEmpty()) {
            currentSongs = originalRecommendedSongs.toList()
            
            // Sync highlight with background playback even on cold restore
            val nowPlaying = NowPlayingState.currentTrack
            if (nowPlaying != null) {
                val matchIndex = currentSongs.indexOfFirst {
                    it.title.equals(nowPlaying.name, ignoreCase = true) &&
                    it.artist.equals(nowPlaying.artist, ignoreCase = true)
                }
                if (matchIndex >= 0) {
                    selectedSongIndex = matchIndex
                }
            } else {
                selectedSongIndex = selectedSongIndex.coerceIn(0, (currentSongs.size - 1).coerceAtLeast(0))
            }
            
            songAdapter.updateSongs(currentSongs, selectedSongIndex)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSpotifyProgress()
        stopMetadataSyncLoop()
        transitionHandler.removeCallbacks(transitionTimeoutRunnable)
        transitionHandler.removeCallbacks(transitionNudgeRunnable)
        stopAlbumPulse()
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        
        // EXIT TRIGGER: If the user is exiting via back button, stop the music
        if (isFinishing) {
            Log.d("HomeActivity", "Activity finishing - sending STOP signal to service")
            val stopIntent = Intent(this, SpotifyBackgroundService::class.java).apply {
                action = SpotifyBackgroundService.ACTION_STOP_MUSIC
            }
            startService(stopIntent)
        }
        
        spotifyPlayerController.disconnect() // Now only clears UI callback, does not kill bridge
    }

    private fun readMoodFromIntent(sourceIntent: Intent?) {
        val prefs = getSharedPreferences(PREF_HOME_STATE, MODE_PRIVATE)

        val scannedEmotionRaw = sourceIntent
            ?.getStringExtra("emotion")
            ?.trim()
            .orEmpty()

        val finalMoodRaw = sourceIntent
            ?.getStringExtra("finalMood")
            ?.trim()
            .orEmpty()

        val savedEmotion = prefs.getString(KEY_LAST_EMOTION, "calm") ?: "calm"
        val savedFinalMood = prefs.getString(KEY_LAST_FINAL_MOOD, savedEmotion) ?: savedEmotion
        val wasManual = prefs.getBoolean("is_manual_mood", false)

        if (scannedEmotionRaw.isNotBlank()) {
            // New scan happened - reset manual override
            emotion = SpotifyMoodQueryBuilder.normalizeMood(scannedEmotionRaw)
            finalMood = if (finalMoodRaw.isNotBlank()) {
                SpotifyMoodQueryBuilder.normalizeMood(finalMoodRaw)
            } else {
                emotion
            }
            isSearchMode = false
            
            prefs.edit()
                .putString(KEY_LAST_EMOTION, emotion)
                .putString(KEY_LAST_FINAL_MOOD, finalMood)
                .putBoolean("is_manual_mood", false)
                .apply()
        } else {
            // Restore from persistence
            emotion = SpotifyMoodQueryBuilder.normalizeMood(savedEmotion)
            finalMood = SpotifyMoodQueryBuilder.normalizeMood(savedFinalMood)
            isSearchMode = wasManual
        }

        Log.d("MOOD_DEBUG", "readMoodFromIntent emotion=$emotion finalMood=$finalMood manual=$isSearchMode")
    }


    private fun setupSpotifyController() {
        spotifyPlayerController = SpotifyPlayerController(
            context = this,
            onStateChanged = { state ->
                runOnUiThread {
                    applySpotifyPlayerState(state)
                }
            },
            onError = { throwable ->
                runOnUiThread {
                    Log.e("PLAYER_DEBUG", "Spotify controller error", throwable)

                    Toast.makeText(
                        this,
                        "Spotify error: ${throwable.message ?: "Unknown error"}",
                        Toast.LENGTH_SHORT
                    ).show()

                    updateSpotifyStatus("Spotify remote failed")
                    updatePlayerModeUI()
                    isConnectingSpotifyRemote = false
                    
                    // CLEAR LOADING on Error
                    isTransitioningTrack = false
                    requestedUri = ""
                    transitionHandler.removeCallbacks(transitionTimeoutRunnable)
                    transitionHandler.removeCallbacks(transitionNudgeRunnable)
                }
            }
        )
    }

    private fun setupProfileButton() {
        binding.imgProfile.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.imgProfile.setOnLongClickListener {
            if (!SpotifyTokenStorage.isAccessTokenValid(this)) {
                Toast.makeText(this, "Linking Spotify account...", Toast.LENGTH_SHORT).show()
                SpotifyAuthManager.startLogin(this)
            } else if (spotifyPlayerController.isConnected()) {
                Toast.makeText(this, "Spotify already connected", Toast.LENGTH_SHORT).show()
            } else {
                connectSpotifyRemote()
            }
            true
        }
    }

    private fun setupTopTabs() {
        binding.tabHome.setOnClickListener {
            // Already on home, do nothing or scroll to top
        }

        binding.tabHistory.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
                .putExtra("emotion", emotion)
                .putExtra("finalMood", finalMood)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }

        binding.tabPlaylist.setOnClickListener {
            val intent = Intent(this, PlaylistActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }

        binding.tabQueue.setOnClickListener {
            val intent = Intent(this, QueueActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }
    }

    private fun setupBottomNav() {
        binding.navHome.setOnClickListener {
            // Already on home, do nothing or scroll to top
        }

        binding.navScan.setOnClickListener {
            openScanWithSessionContext()
        }

        binding.navProfile.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }
    }

    private fun setupCenterScanButton() {
        binding.btnCenterScan.setOnClickListener {
            openScanWithSessionContext()
        }
    }

    private fun setupRecycler() {
        songAdapter = RecommendedSongAdapter(
            songs = emptyList(),
            selectedIndex = selectedSongIndex,
            onSongClick = { clickedIndex ->
                if (clickedIndex in currentSongs.indices) {
                    selectSong(clickedIndex)
                }
            },
            onAddClick = { song ->
                showAddToPlaylistDialog(song)
            },
            onQueueClick = { song ->
                QueueManager.addToQueue(song)
                Toast.makeText(this, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
            },
            onLongClick = { song ->
                showSongOptionsDialog(song)
            }
        )

        binding.recyclerRecommendations.layoutManager = LinearLayoutManager(this)
        binding.recyclerRecommendations.adapter = songAdapter
    }

    private fun setupSearchBar() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim().orEmpty()
                if (query.isBlank()) {
                    restoreRecommendedSongs()
                } else {
                    viewModel.performSearch(query)
                }
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun setupPlayerButtons() {
        binding.btnPrevious.setOnClickListener {
            if (spotifyPlayerController.isConnected()) {
                if (NowPlayingState.activeSessionId.isNotBlank()) {
                    HistoryStorage.markSessionReplayed(this, NowPlayingState.activeSessionId)
                }
                resetPlayerUIForTransition()
                spotifyPlayerController.previous()
            } else {
                Toast.makeText(this, "Connect Spotify first", Toast.LENGTH_SHORT).show()
                connectSpotifyRemote()
            }
        }

        binding.btnNext.setOnClickListener {
            handleQueueNext()
        }

        binding.btnPlayPause.setOnClickListener {
            if (!spotifyPlayerController.isConnected()) {
                Toast.makeText(this, "Connecting Spotify...", Toast.LENGTH_SHORT).show()
                connectSpotifyRemote()
                return@setOnClickListener
            }

            if (spotifyIsPlaying) {
                spotifyPlayerController.pause()
            } else {
                spotifyPlayerController.resume()
            }
        }

        binding.seekMusic.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || currentSongDurationSeconds <= 0 || !spotifyPlayerController.isConnected()) return

                isUserSeeking = true
                currentProgressSeconds = progress.coerceIn(0, currentSongDurationSeconds)
                binding.tvCurrentTime.text = secondsToTime(currentProgressSeconds)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                if (!spotifyPlayerController.isConnected()) return
                isUserSeeking = true
                stopSpotifyProgress()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (!spotifyPlayerController.isConnected()) {
                    isUserSeeking = false
                    return
                }

                val positionMs = (currentProgressSeconds * 1000L)
                    .coerceAtLeast(0L)
                    .coerceAtMost(currentSongDurationSeconds * 1000L)

                spotifyPlayerController.seekTo(positionMs)
                lastRemotePositionMs = positionMs
                lastRemoteStateElapsedMs = SystemClock.elapsedRealtime()

                if (spotifyIsPlaying) startSpotifyProgress()
                isUserSeeking = false
            }
        })
    }

    private fun setupVolumeControls() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        
        binding.seekVolume.max = maxVolume
        binding.seekVolume.progress = currentVolume
        
        binding.seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                    if (progress > 0) {
                        isMuted = false
                        binding.btnMuteToggle.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        binding.btnMuteToggle.setOnClickListener {
            if (isMuted) {
                // Unmute
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, lastVolume, 0)
                binding.seekVolume.progress = lastVolume
                binding.btnMuteToggle.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                isMuted = false
            } else {
                // Mute
                lastVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                binding.seekVolume.progress = 0
                binding.btnMuteToggle.setImageResource(android.R.drawable.ic_lock_silent_mode)
                isMuted = true
            }
        }
    }

    private fun setupStickyPlayer() {
        binding.homeScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            // Large player bottom is around 380dp.
            val playerThreshold = (380 * resources.displayMetrics.density).toInt()
            
            // Forecast card is near the bottom. Let's show pill when scrolled significantly.
            // Or better, when the main forecast card might be out of view.
            val forecastThreshold = (500 * resources.displayMetrics.density).toInt()

            // Sticky Player Toggle
            if (scrollY > playerThreshold) {
                if (binding.miniPlayerStickyTop.visibility != View.VISIBLE) {
                    binding.miniPlayerStickyTop.visibility = View.VISIBLE
                    binding.miniPlayerStickyTop.alpha = 0f
                    binding.miniPlayerStickyTop.animate().alpha(1f).setDuration(200).start()
                }
            } else {
                if (binding.miniPlayerStickyTop.visibility == View.VISIBLE) {
                    binding.miniPlayerStickyTop.animate().alpha(0f).setDuration(200).withEndAction {
                        binding.miniPlayerStickyTop.visibility = View.GONE
                    }.start()
                }
            }

            // Forecast Hover Toggle
            if (scrollY > forecastThreshold) {
                if (binding.cardForecastHover.visibility != View.VISIBLE) {
                    binding.cardForecastHover.visibility = View.VISIBLE
                    binding.cardForecastHover.alpha = 0f
                    binding.cardForecastHover.animate().alpha(1f).setDuration(250).start()
                }
            } else {
                if (binding.cardForecastHover.visibility == View.VISIBLE) {
                    binding.cardForecastHover.animate().alpha(0f).setDuration(200).withEndAction {
                        binding.cardForecastHover.visibility = View.GONE
                    }.start()
                }
            }
        }

        binding.btnStickyPlayPause.setOnClickListener {
            if (spotifyIsPlaying) {
                spotifyPlayerController.pause()
            } else {
                spotifyPlayerController.resume()
            }
        }

        binding.btnStickyNext.setOnClickListener {
            handleQueueNext()
        }

        binding.btnStickyPrevious.setOnClickListener {
            if (NowPlayingState.activeSessionId.isNotBlank()) {
                HistoryStorage.markSessionReplayed(this, NowPlayingState.activeSessionId)
            }
            resetPlayerUIForTransition()
            spotifyPlayerController.previous()
        }

        binding.cardForecastHover.setOnClickListener {
            try {
                startActivity(Intent(this, ForecastActivity::class.java))
            } catch (_: Exception) { }
        }

        binding.btnStickySave.setOnClickListener {
            val track = NowPlayingState.currentTrack
            if (track != null) {
                showAddToPlaylistDialog(Song(track.name, track.artist, secondsToTime((NowPlayingState.durationMs/1000).toInt())))
            }
        }
    }

    private fun setupSaveCurrentTrackButtons() {
        binding.btnSaveCurrent.setOnClickListener {
            val track = NowPlayingState.currentTrack
            if (track != null) {
                showAddToPlaylistDialog(Song(track.name, track.artist, secondsToTime((NowPlayingState.durationMs/1000).toInt())))
            } else {
                Toast.makeText(this, "No song playing", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupForecastPreview() {
        val openForecast = View.OnClickListener {
            try {
                startActivity(Intent(this, ForecastActivity::class.java))
            } catch (_: Exception) {
                Toast.makeText(this, "Forecast page unavailable", Toast.LENGTH_SHORT).show()
            }
        }

        binding.cardForecast.setOnClickListener(openForecast)
        binding.btnViewForecast.setOnClickListener(openForecast)
    }

    private fun loadEmotionSongs() {
        applyEmotionTheme(finalMood)
        viewModel.loadRecommendations(emotion, finalMood, isSearchMode)
    }


    private fun preloadImportantMoodsOnly() {
        val predictedMood = try {
            SpotifyMoodQueryBuilder.normalizeMood(
                MoodPredictionEngine(this)
                    .predictCurrentSlot()
                    .predictedMood
            )
        } catch (_: Exception) {
            ""
        }

        val moodsToPreload = listOf(finalMood, predictedMood)
            .map { SpotifyMoodQueryBuilder.normalizeMood(it) }
            .filter { it.isNotBlank() }
            .distinct()

        lifecycleScope.launch(Dispatchers.IO) {
            moodsToPreload.forEach { mood ->
                if (!MoodRecommendationCache.has(mood)) {
                    try {
                        val result = SpotifyBackedRecommendationEngine(this@HomeActivity)
                            .getRecommendations(
                                currentMood = mood,
                                timeOfDay = getCurrentTimeOfDay(),
                                limit = 10
                            )

                        MoodRecommendationCache.set(
                            this@HomeActivity,
                            mood,
                            MoodRecommendationCache.CachedResult(
                                songs = result.songs,
                                trackCache = result.trackCache
                            )
                        )

                    } catch (e: Exception) {
                        Log.e("SpotifySearch", "Background recommendation load failed", e)
                        e.printStackTrace()
                    }
                }
            }
        }
    }


    private fun onMoodAfterSelected(mood: String) {
        if (NowPlayingState.activeSessionId.isBlank()) {
            Toast.makeText(this, "No active session", Toast.LENGTH_SHORT).show()
            return
        }

        val cleanMood = SpotifyMoodQueryBuilder.normalizeMood(mood)

        HistoryStorage.updateSessionMoodAfter(
            this,
            NowPlayingState.activeSessionId,
            cleanMood
        )

        finalMood = cleanMood
        emotion = cleanMood
        currentSearchMood = cleanMood
        hasAutoplayedThisSession = false

        getSharedPreferences(PREF_HOME_STATE, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_EMOTION, emotion)
            .putString(KEY_LAST_FINAL_MOOD, finalMood)
            .apply()

        applyEmotionTheme(finalMood)
        loadForecastPreviewText()

        spotifyTrackCache.clear()
        currentSongs = emptyList()
        originalRecommendedSongs = emptyList()
        selectedSongIndex = 0
        songAdapter.updateSongs(emptyList(), 0)

        isSearchMode = false
        lastSearchQuery = ""
        isLoadingRemoteRecommendations = false

        loadEmotionSongs()

        binding.moodAfterContainer.visibility = View.GONE

        Toast.makeText(
            this,
            "Mood updated: ${formatMood(cleanMood)}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun restoreRecommendedSongs() {
        viewModel.performSearch("")
        viewModel.loadRecommendations(emotion, finalMood)
        isSearchMode = false
        currentSearchMood = ""

        currentSongs = originalRecommendedSongs.toList()
        selectedSongIndex = selectedSongIndex.coerceIn(
            0,
            (currentSongs.size - 1).coerceAtLeast(0)
        )

        songAdapter.updateSongs(currentSongs, selectedSongIndex)

        binding.tvMoodLabel.text =
            "Detected: ${formatMood(emotion)} | Smart Mood: ${formatMood(finalMood)}"
    }

    private fun normalizedSongKey(title: String, artist: String): String {
        return "${title.trim().lowercase(Locale.getDefault())}|${artist.trim().lowercase(Locale.getDefault())}"
    }

    private fun cacheTrackForSong(song: Song, track: SpotifyTrack) {
        spotifyTrackCache[normalizedSongKey(song.title, song.artist)] = track
        spotifyTrackCache[normalizedSongKey(track.name, track.artist)] = track

        if (track.uri.isNotBlank()) {
            spotifyTrackCache[track.uri] = track
        }
    }

    private fun rebuildSpotifyTrackCache(
        songs: List<Song>,
        incomingCache: Map<String, SpotifyTrack>,
        clearExisting: Boolean = true
    ) {
        if (clearExisting) {
            spotifyTrackCache.clear()
        }

        fun clean(value: String): String {
            return value
                .trim()
                .lowercase(Locale.getDefault())
                .replace(Regex("\\s+"), " ")
        }

        fun relaxedTitle(value: String): String {
            return clean(value)
                .replace(Regex("\\(.*?\\)"), "")
                .replace(Regex("-.*$"), "")
                .trim()
        }

        incomingCache.forEach { entry ->
            val track = entry.value

            spotifyTrackCache[clean(entry.key)] = track
            spotifyTrackCache[normalizedSongKey(track.name, track.artist)] = track

            if (track.uri.isNotBlank()) {
                spotifyTrackCache[track.uri] = track
            }
        }

        songs.forEach { song ->
            val songKey = normalizedSongKey(song.title, song.artist)
            val songTitle = relaxedTitle(song.title)
            val songArtist = clean(song.artist)

            val directTrack =
                spotifyTrackCache[songKey]
                    ?: incomingCache.values.firstOrNull { track ->
                        normalizedSongKey(track.name, track.artist) == songKey
                    }
                    ?: incomingCache.values.firstOrNull { track ->
                        relaxedTitle(track.name) == songTitle &&
                                clean(track.artist) == songArtist
                    }
                    ?: incomingCache.values.firstOrNull { track ->
                        val trackTitle = relaxedTitle(track.name)
                        val trackArtist = clean(track.artist)

                        (trackTitle.contains(songTitle) || songTitle.contains(trackTitle)) &&
                                (trackArtist.contains(songArtist) || songArtist.contains(trackArtist))
                    }

            if (directTrack != null) {
                cacheTrackForSong(song, directTrack)
            }
        }

        Log.d("CACHE_DEBUG", "Songs size: ${songs.size}")
        Log.d("CACHE_DEBUG", "Incoming cache size: ${incomingCache.size}")
        Log.d("CACHE_DEBUG", "Final cache size: ${spotifyTrackCache.size}")
    }

    private fun findCachedTrackForSong(song: Song): SpotifyTrack? {
        return spotifyTrackCache[normalizedSongKey(song.title, song.artist)]
            ?: spotifyTrackCache.values.firstOrNull { track ->
                track.name.equals(song.title, ignoreCase = true) &&
                        track.artist.equals(song.artist, ignoreCase = true)
            }
            ?: spotifyTrackCache.values.firstOrNull { track ->
                track.name.contains(song.title, ignoreCase = true) ||
                        song.title.contains(track.name, ignoreCase = true)
            }
    }

    private fun findCachedTrackByUri(uri: String): SpotifyTrack? {
        if (uri.isBlank()) return null
        return spotifyTrackCache[uri]
            ?: spotifyTrackCache.values.firstOrNull { it.uri == uri }
    }

    private fun selectSong(index: Int) {
        if (index !in currentSongs.indices) return

        val now = System.currentTimeMillis()
        if (now - lastSongClickTime < 500L) {
            return
        }
        lastSongClickTime = now

        selectedSongIndex = index
        val song = currentSongs[index]
        songAdapter.updateSelectedIndex(index)

        val cachedTrack = findCachedTrackForSong(song)
        resetPlayerUIForTransition(uri = cachedTrack?.uri ?: "", track = cachedTrack)

        if (cachedTrack == null) {
            if (song.title.endsWith("...") || song.artist == "eMoodtune AI") {
                Toast.makeText(this, "Still loading real songs, please wait...", Toast.LENGTH_SHORT).show()
                return
            }

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val searchManager = SpotifySearchManager(this@HomeActivity)
                    
                    // RETRY LOGIC: Try specific search first, then broader search
                    var result = searchManager.search(
                        query = "${song.title} ${song.artist}",
                        limit = 5,
                        market = "PH"
                    )
                    
                    var found = result.trackCache.values.firstOrNull { track ->
                        track.name.equals(song.title, ignoreCase = true) ||
                                track.name.contains(song.title, ignoreCase = true)
                    }

                    if (found == null) {
                        Log.d("PLAYER_DEBUG", "Primary search failed for ${song.title}, retrying broad search...")
                        result = searchManager.search(
                            query = song.title,
                            limit = 10,
                            market = "PH"
                        )
                        found = result.trackCache.values.firstOrNull { track ->
                            track.name.equals(song.title, ignoreCase = true) ||
                                    track.name.contains(song.title, ignoreCase = true)
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (found != null) {
                            rebuildSpotifyTrackCache(listOf(song), result.trackCache, clearExisting = false)
                            currentSpotifyTrack = found
                            requestSpotifyPlayback(song, found)
                        } else {
                            // RESET UI on failure
                            isTransitioningTrack = false
                            requestedUri = ""
                            binding.tvSongTitle.text = "Ready to play"
                            binding.tvArtist.text = "Track not found"
                            
                            Toast.makeText(
                                this@HomeActivity,
                                "Could not find \"${song.title}\" on Spotify",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        // RESET UI on crash
                        isTransitioningTrack = false
                        requestedUri = ""
                        binding.tvSongTitle.text = "Search Error"
                        
                        Toast.makeText(
                            this@HomeActivity,
                            "Search failed: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            return
        }

        currentSpotifyTrack = cachedTrack

        // SMART PLAYBACK: If we are selecting the exact same song that is currently paused, just resume it.
        // This makes the UI feel much more responsive and fixes the "pause bug".
        val isSameTrack = NowPlayingState.currentTrack?.uri == cachedTrack.uri
        if (isSameTrack && !spotifyIsPlaying) {
            spotifyPlayerController.resume()
        } else {
            requestSpotifyPlayback(song, cachedTrack)
        }
    }

    private fun requestSpotifyPlayback(song: Song, track: SpotifyTrack) {
        val uri = track.uri
        if (uri.isBlank()) return

        // OPTIMISTIC UI: Update player info immediately before network confirms
        binding.tvSongTitle.text = track.name
        binding.tvArtist.text = track.artist
        binding.tvStickySongTitle.text = track.name
        binding.tvStickyArtist.text = track.artist
        
        if (track.albumImageUrl.isNotBlank()) {
            loadAlbumArtFromUrl(track.albumImageUrl)
        }

        requestedUri = uri
        isTransitioningTrack = true
        transitionStartTime = System.currentTimeMillis()

        pendingSpotifyOpen = true
        pendingSpotifyUri = uri
        pendingSpotifySong = song

        playSpotifyUri(uri)
    }

    private fun loadSpotifyTrack(song: Song, autoPlay: Boolean = true) {
        val cachedTrack = findCachedTrackForSong(song)

        if (cachedTrack != null) {
            currentSpotifyTrack = cachedTrack

            if (autoPlay) {
                requestSpotifyPlayback(song, cachedTrack)
            }

            return
        }

        Toast.makeText(
            this,
            "Spotify is still loading. Try again in a few seconds.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun resetPlayerUIForTransition(uri: String = "", track: SpotifyTrack? = null) {
        isTransitioningTrack = true
        requestedUri = uri
        transitionStartTime = System.currentTimeMillis()
        
        // Safety timeout: 12 seconds to resolve metadata or fail
        transitionHandler.removeCallbacks(transitionTimeoutRunnable)
        transitionHandler.removeCallbacks(transitionNudgeRunnable)
        
        transitionHandler.postDelayed(transitionNudgeRunnable, 4000)
        transitionHandler.postDelayed(transitionTimeoutRunnable, 12000)

        currentProgressSeconds = 0
        lastRemotePositionMs = 0L
        lastRemoteStateElapsedMs = SystemClock.elapsedRealtime()
        updateSeekUI()
        
        // OPTIMISTIC UI: Only clear if we don't know what's coming
        if (track != null) {
            binding.tvSongTitle.text = track.name
            binding.tvArtist.text = track.artist
            binding.tvStickySongTitle.text = track.name
            binding.tvStickyArtist.text = track.artist
        } else {
            binding.tvSongTitle.text = "Loading..."
            binding.tvStickySongTitle.text = "Loading..."
        }
    }

    private fun playSpotifyUri(uri: String) {
        if (uri.isBlank()) return

        Log.d("PLAYER_DEBUG", "Requesting Spotify playback: $uri")
        
        val cached = findCachedTrackByUri(uri)
        resetPlayerUIForTransition(uri, cached)

        if (!spotifyPlayerController.isConnected()) {
            connectSpotifyRemote {
                playSpotifyUri(uri)
            }
            return
        }

        spotifyPlayerController.play(uri) {
            Log.d("PLAYER_DEBUG", "Spotify play command accepted: $uri")
        }
    }

    private var lastSavedSpotifyUri: String? = null

    private fun applySpotifyPlayerState(state: PlayerState) {
        val track = state.track
        
        // Handle metadata UI even if track is null (initial connection state)
        if (track == null || track.uri.isBlank()) {
            // If we are currently transitioning, ignore the "null" event as it might be a transition flicker
            if (isTransitioningTrack) return

            // If it's truly empty and we aren't waiting for a song, clear UI
            if (NowPlayingState.currentTrack == null) {
                if (isConnectingSpotifyRemote) {
                    binding.tvSongTitle.text = "Synchronizing..."
                    binding.tvArtist.text = "Fetching Spotify metadata"
                } else {
                    binding.tvSongTitle.text = "Ready to play"
                    binding.tvArtist.text = "eMoodtune Bridge"
                    
                    // START SYNC LOOP: Proactively look for background music
                    startMetadataSyncLoop()
                }
                setDefaultAlbumArt()
                binding.tvStickySongTitle.text = binding.tvSongTitle.text
                binding.tvStickyArtist.text = binding.tvArtist.text
                binding.imgStickyAlbumArt.setImageResource(R.drawable.emoodtune_logo)
            }
            
            NowPlayingState.currentTrack = null
            
            // Still update state flags so connection UI remains responsive
            spotifyIsPlaying = !state.isPaused
            isConnectingSpotifyRemote = false
            updateSpotifyStatus()
            updatePlayerModeUI()
            updatePlayPauseUI()
            return
        }

        val name = track.name
        val artist = track.artist.name
        val uri = track.uri

        // Transition Guard: Ignore events for the "old" song if we just requested a new one
        if (isTransitioningTrack && requestedUri.isNotBlank() && uri != requestedUri) {
            val waitTime = System.currentTimeMillis() - transitionStartTime
            if (waitTime < 5000L) {
                Log.d("PLAYER_SYNC", "Ignoring stale event for $uri while waiting for $requestedUri ($waitTime ms)")
                return
            } else {
                Log.d("PLAYER_SYNC", "Transition Guard: Allowing fall-through after $waitTime ms for $uri")
            }
        }
        
        // UNLOCK UI: If we get a valid track while transitioning, unlock regardless of URI match
        // to prevent getting stuck in "Loading..." if the URI changed unexpectedly
        if (isTransitioningTrack) {
            isTransitioningTrack = false
            requestedUri = ""
            transitionHandler.removeCallbacks(transitionTimeoutRunnable)
            transitionHandler.removeCallbacks(transitionNudgeRunnable)
        }

        stopMetadataSyncLoop()

        val durationSeconds = (track.duration / 1000L).toInt().coerceAtLeast(0)

        // Robust duplication check: If the app was closed, restore the log status from storage
        if (NowPlayingState.currentTrack == null) {
            val lastLogged = getSharedPreferences(PREF_HOME_STATE, MODE_PRIVATE)
                .getString(KEY_LAST_LOGGED_URI, "") ?: ""
            
            if (lastLogged.isNotBlank() && lastLogged == uri) {
                NowPlayingState.lastLoggedUri = lastLogged
                NowPlayingState.hasLoggedCurrentTrack = true
            }
        }

        if (NowPlayingState.currentTrack?.uri != uri) {
            // It's a physically different song, so reset flags
            if (NowPlayingState.hasLoggedCurrentTrack && NowPlayingState.currentTrack?.uri != null) {
                // Only reset if we were previously playing a different track
                NowPlayingState.currentTrackMood = null 
                NowPlayingState.hasLoggedCurrentTrack = false 
                NowPlayingState.activeSessionId = "" // Crucial fix for skipped songs retaining old Session IDs
                
                getSharedPreferences(PREF_HOME_STATE, MODE_PRIVATE).edit()
                    .putString(KEY_LAST_LOGGED_URI, "")
                    .apply()
            }

            // RESET TIMER: Start fresh 60s timer for the new song
            sessionStartTime = System.currentTimeMillis()
            hasAskedMoodAfter = false
            binding.moodAfterContainer.visibility = View.GONE
        }
        
        spotifyCurrentUri = uri
        spotifyIsPlaying = !state.isPaused

        currentSongDurationSeconds = durationSeconds
        currentProgressSeconds = (state.playbackPosition / 1000L).toInt()
            .coerceIn(0, currentSongDurationSeconds)

        lastRemotePositionMs = state.playbackPosition.coerceAtLeast(0L)
        lastRemoteStateElapsedMs = SystemClock.elapsedRealtime()

        val cachedByUri = findCachedTrackByUri(uri)

        val existingArtwork = when {
            cachedByUri?.albumImageUrl?.isNotBlank() == true -> cachedByUri.albumImageUrl
            currentSpotifyTrack?.uri == uri && currentSpotifyTrack?.albumImageUrl?.isNotBlank() == true ->
                currentSpotifyTrack?.albumImageUrl.orEmpty()
            NowPlayingState.currentTrack?.uri == uri && NowPlayingState.currentTrack?.albumImageUrl?.isNotBlank() == true ->
                NowPlayingState.currentTrack?.albumImageUrl.orEmpty()
            else -> ""
        }

        currentSpotifyTrack = SpotifyTrack(
            id = cachedByUri?.id ?: uri,
            name = name,
            artist = artist,
            albumName = cachedByUri?.albumName ?: currentSpotifyTrack?.albumName.orEmpty(),
            albumImageUrl = existingArtwork,
            spotifyUrl = cachedByUri?.spotifyUrl ?: uri,
            uri = uri
        )

        binding.tvSongTitle.text = name
        binding.tvArtist.text = artist
        binding.tvDuration.text = secondsToTime(currentSongDurationSeconds)
        binding.seekMusic.max = currentSongDurationSeconds

        // Update Sticky Player
        binding.tvStickySongTitle.text = name
        binding.tvStickyArtist.text = artist
        if (spotifyIsPlaying) {
            binding.btnStickyPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        } else {
            binding.btnStickyPlayPause.setImageResource(android.R.drawable.ic_media_play)
        }

        val imageUri = track.imageUri

        if (imageUri != null && spotifyPlayerController.isConnected()) {
            try {
                loadAlbumArtFromSpotifyRemote(imageUri)
            } catch (_: Exception) {
                setDefaultAlbumArt()
            }
        } else {
            setDefaultAlbumArt()
        }
        
        // Sync sticky artwork
        binding.imgStickyAlbumArt.setImageDrawable(binding.imgAlbumArt.drawable)

        if (pendingSpotifyOpen && uri == pendingSpotifyUri) {
            val playedSong = pendingSpotifySong
            clearPendingSpotifyRequest()

            if (playedSong != null) {
                startRecommendationSession(playedSong)
                sessionStartTime = System.currentTimeMillis()
                hasAskedMoodAfter = false
                binding.moodAfterContainer.visibility = View.GONE
                markCurrentSessionPlayedIfNeeded()
                
                // Update background logging context
                NowPlayingState.moodBefore = resolveHistoryMood()
                NowPlayingState.source = if (isSearchMode) "search" else if (currentRecommendationSessionId.isNotBlank()) "recommendation" else "scan"
                NowPlayingState.activeSessionId = if (currentRecommendationSessionId.isNotBlank()) currentRecommendationSessionId else UUID.randomUUID().toString()
            }
        }

        // AUTO-ADVANCE: Detect natural song end (approx 1s before end)
        if (spotifyIsPlaying && currentSongDurationSeconds > 0) {
            val remainingMs = (currentSongDurationSeconds * 1000L) - state.playbackPosition
            val now = System.currentTimeMillis()
            
            if (remainingMs in 0..1500L && QueueManager.queue.value?.isNotEmpty() == true && (now - lastAutoAdvanceTime > 2000L)) {
                lastAutoAdvanceTime = now
                handleQueueNext()
            }
        }

        updateSeekUI()
        updatePlayPauseUI()

        val matchedIndex = currentSongs.indexOfFirst {
            it.title.equals(name, ignoreCase = true) &&
                    it.artist.equals(artist, ignoreCase = true)
        }

        if (matchedIndex >= 0 && matchedIndex != selectedSongIndex) {
            selectedSongIndex = matchedIndex
            songAdapter.updateSelectedIndex(selectedSongIndex)
        }

        NowPlayingState.currentTrack = currentSpotifyTrack
        NowPlayingState.isPlaying = spotifyIsPlaying
        NowPlayingState.playbackPositionMs = state.playbackPosition
        NowPlayingState.durationMs = track.duration

        isConnectingSpotifyRemote = false
        updateSpotifyStatus()
        updatePlayerModeUI()

        syncHighlightWithCurrentTrack()

        if (spotifyIsPlaying) {
            startSpotifyProgress()
        } else {
            stopSpotifyProgress()
        }
    }

    private fun clearPendingSpotifyRequest() {
        pendingSpotifyOpen = false
        pendingSpotifyUri = ""
        pendingSpotifySong = null
    }

    private fun restoreNowPlayingUI() {
        val track = NowPlayingState.currentTrack ?: return

        currentSpotifyTrack = track
        spotifyCurrentUri = track.uri
        spotifyIsPlaying = NowPlayingState.isPlaying

        binding.tvSongTitle.text = track.name
        binding.tvArtist.text = track.artist

        currentSongDurationSeconds = (NowPlayingState.durationMs / 1000L).toInt().coerceAtLeast(0)
        currentProgressSeconds = (NowPlayingState.playbackPositionMs / 1000L).toInt()
            .coerceIn(0, currentSongDurationSeconds)

        binding.seekMusic.max = currentSongDurationSeconds
        binding.tvDuration.text = secondsToTime(currentSongDurationSeconds)

        // Sync Sticky Player
        binding.tvStickySongTitle.text = track.name
        binding.tvStickyArtist.text = track.artist
        if (spotifyIsPlaying) {
            binding.btnStickyPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        } else {
            binding.btnStickyPlayPause.setImageResource(android.R.drawable.ic_media_play)
        }

        if (track.albumImageUrl.isNotBlank()) {
            loadAlbumArtFromUrl(track.albumImageUrl)
            // Sync sticky art
            lifecycleScope.launch {
                delay(500) // Wait for main art to load
                binding.imgStickyAlbumArt.setImageDrawable(binding.imgAlbumArt.drawable)
            }
        } else {
            setDefaultAlbumArt()
            binding.imgStickyAlbumArt.setImageResource(R.drawable.emoodtune_logo)
        }

        updateSeekUI()
        updatePlayPauseUI()

        // Sync recommendation list highlight
        if (currentSongs.isNotEmpty()) {
            val matchIndex = currentSongs.indexOfFirst {
                it.title.equals(track.name, ignoreCase = true) &&
                it.artist.equals(track.artist, ignoreCase = true)
            }
            if (matchIndex >= 0) {
                selectedSongIndex = matchIndex
                songAdapter.updateSelectedIndex(selectedSongIndex)
            }
        }

        if (spotifyPlayerController.isConnected() && spotifyIsPlaying) {
            lastRemotePositionMs = NowPlayingState.playbackPositionMs
            lastRemoteStateElapsedMs = SystemClock.elapsedRealtime()
            startSpotifyProgress()
        } else {
            stopSpotifyProgress()
        }
    }

    private fun connectSpotifyRemote(onConnected: (() -> Unit)? = null) {
        if (spotifyPlayerController.isConnected()) {
            if (pendingSpotifyOpen && pendingSpotifyUri.isNotBlank()) {
                spotifyPlayerController.play(pendingSpotifyUri)
            }
            onConnected?.invoke()
            return
        }

        if (isConnectingSpotifyRemote) {
            onConnected?.let { 
                spotifyPlayerController.connect(showAuth = true, onConnected = it)
            }
            return
        }

        val spotifyInstalled = try {
            packageManager.getPackageInfo("com.spotify.music", 0)
            true
        } catch (_: Exception) {
            false
        }

        if (!spotifyInstalled) {
            updateSpotifyStatus("Spotify app not installed")
            Toast.makeText(this, "Install Spotify first", Toast.LENGTH_SHORT).show()
            return
        }

        isConnectingSpotifyRemote = true
        updateSpotifyStatus("Seamlessly connecting...")
        updatePlayerModeUI()
        
        // Instant UI feedback for the connection phase
        if (NowPlayingState.currentTrack == null) {
            binding.tvSongTitle.text = "Synchronizing..."
            binding.tvArtist.text = "Connecting to Spotify"
        }

        // FOREGROUND CONNECTION: Allow Auth UI if needed
        spotifyPlayerController.connect(
            showAuth = true,
            onConnected = {
                runOnUiThread {
                    isConnectingSpotifyRemote = false
                    updateSpotifyStatus()
                    updatePlayerModeUI()

                    if (pendingSpotifyOpen && pendingSpotifyUri.isNotBlank()) {
                        spotifyPlayerController.play(pendingSpotifyUri)
                    } else {
                        // RE-SYNC HANDSHAKE: Check if Spotify is already playing music
                        spotifyPlayerController.getPlayerState { state ->
                            val activeTrack = state.track
                            if (activeTrack != null) {
                                // Background music detected - mark autoplay as "done" so we don't hijack later
                                hasAutoplayedThisSession = true
                                Log.d("PLAYER_SYNC", "Background playback detected immediately.")
                            } else {
                                // FIRST CHECK FAILED (null): Wait 1.2s and check one more time before autoplaying
                                // This solves the "slow loading/placeholder" bug on cold starts
                                Handler(Looper.getMainLooper()).postDelayed({
                                    spotifyPlayerController.getPlayerState { secondState ->
                                        val secondTrack = secondState.track
                                        if (!hasAutoplayedThisSession && currentSongs.isNotEmpty()) {
                                            if (secondTrack == null) {
                                                Log.d("PLAYER_SYNC", "Handshake confirmed idle. Autoplaying.")
                                                hasAutoplayedThisSession = true
                                                runOnUiThread { selectSong(0) }
                                            } else {
                                                Log.d("PLAYER_SYNC", "Background playback detected on second check.")
                                                hasAutoplayedThisSession = true
                                            }
                                        }
                                    }
                                }, 1200)
                            }
                        }
                    }
                    onConnected?.invoke()
                }
            }
        )
    }

    private fun loadSpotifyProfile() {
        lifecycleScope.launch {
            val accessToken = withContext(Dispatchers.IO) {
                SpotifySessionManager.getValidAccessToken(this@HomeActivity)
            }

            if (accessToken.isNullOrBlank()) {
                spotifyDisplayName = ""
                updateSpotifyStatus()
                updatePlayerModeUI()
                return@launch
            }

            val profile = withContext(Dispatchers.IO) {
                SpotifyRepository.getCurrentUserProfile(accessToken)
            }

            spotifyDisplayName = profile?.displayName ?: ""
            updateSpotifyStatus()
            updatePlayerModeUI()
        }
    }

    private fun updateSpotifyStatus(status: String? = null) {
        binding.tvSpotifyStatus.text = when {
            !status.isNullOrBlank() -> status
            spotifyPlayerController.isConnected() && spotifyDisplayName.isNotBlank() ->
                "Connected as $spotifyDisplayName"
            spotifyPlayerController.isConnected() ->
                "Spotify Connected"
            isConnectingSpotifyRemote ->
                "Seamlessly connecting..."
            SpotifyTokenStorage.isAccessTokenValid(this) && spotifyDisplayName.isNotBlank() ->
                "Spotify Linked • $spotifyDisplayName"
            SpotifyTokenStorage.isAccessTokenValid(this) ->
                "Spotify Linked"
            else -> "Spotify Not Connected"
        }
    }

    private fun updatePlayerModeUI() {
        binding.tvSpotifyStatus.text = when {
            spotifyPlayerController.isConnected() && spotifyDisplayName.isNotBlank() ->
                "Live Playback • $spotifyDisplayName"
            spotifyPlayerController.isConnected() ->
                "Live Playback"
            isConnectingSpotifyRemote ->
                "Seamlessly connecting..."
            SpotifyTokenStorage.isAccessTokenValid(this) && spotifyDisplayName.isNotBlank() ->
                "Spotify Linked • $spotifyDisplayName"
            SpotifyTokenStorage.isAccessTokenValid(this) ->
                "Spotify Linked"
            else -> "Spotify Not Connected"
        }
    }

    private fun loadAlbumArtFromUrl(imageUrl: String) {
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    URL(imageUrl).openStream().use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
                if (bitmap != null) {
                    binding.imgAlbumArt.setImageBitmap(bitmap)
                    binding.imgAlbumArt.alpha = 1f
                    binding.imgStickyAlbumArt.setImageBitmap(bitmap)
                }
            } catch (_: Exception) {
                setDefaultAlbumArt()
            }
        }
    }

    private fun setDefaultAlbumArt() {
        binding.imgAlbumArt.setImageResource(R.drawable.emoodtune_logo)
        binding.imgAlbumArt.alpha = 0.95f
    }

    private fun startSpotifyProgress() {
        progressHandler.removeCallbacks(spotifyProgressRunnable)
        progressHandler.post(spotifyProgressRunnable)
    }

    private fun stopSpotifyProgress() {
        progressHandler.removeCallbacks(spotifyProgressRunnable)
    }

    private fun startMetadataSyncLoop() {
        syncHandler.removeCallbacks(syncMetadataRunnable)
        syncHandler.post(syncMetadataRunnable)
    }

    private fun stopMetadataSyncLoop() {
        syncHandler.removeCallbacks(syncMetadataRunnable)
    }

    private fun handleQueueNext() {
        if (!spotifyPlayerController.isConnected()) {
            Toast.makeText(this, "Seamlessly connecting Spotify...", Toast.LENGTH_SHORT).show()
            connectSpotifyRemote {
                handleQueueNext()
            }
            return
        }

        if (NowPlayingState.activeSessionId.isNotBlank()) {
            HistoryStorage.markSessionSkipped(this, NowPlayingState.activeSessionId)
        }

        val nextInQueue = QueueManager.playNext()
        if (nextInQueue != null) {
            val track = findCachedTrackForSong(nextInQueue)
            if (track != null) {
                playSpotifyUri(track.uri)
                Toast.makeText(this, "Playing from queue: ${nextInQueue.title}", Toast.LENGTH_SHORT).show()
            } else {
            resetPlayerUIForTransition()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val result = SpotifySearchManager(this@HomeActivity).search(
                        query = "${nextInQueue.title} ${nextInQueue.artist}",
                        limit = 1,
                        market = "PH"
                    )
                    val found = result.trackCache.values.firstOrNull()
                    withContext(Dispatchers.Main) {
                        if (found != null) {
                            playSpotifyUri(found.uri)
                        } else {
                            spotifyPlayerController.next()
                        }
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) { spotifyPlayerController.next() }
                }
            }
            }
        } else {
            resetPlayerUIForTransition()
            spotifyPlayerController.next()
        }
    }

    private fun updateSeekUI() {
        if (currentSongDurationSeconds <= 0) {
            binding.seekMusic.max = 100
            binding.seekMusic.progress = 0
            binding.tvCurrentTime.text = "0:00"
            return
        }

        binding.seekMusic.max = currentSongDurationSeconds
        binding.seekMusic.progress = currentProgressSeconds.coerceIn(0, currentSongDurationSeconds)
        binding.tvCurrentTime.text = secondsToTime(currentProgressSeconds)
    }

    private fun setupMoodAfterButtons() {
        binding.btnMoodHappy.setOnClickListener {
            Toast.makeText(this, "Happy clicked", Toast.LENGTH_SHORT).show()
            onMoodAfterSelected("happy")
        }

        binding.btnMoodCalm.setOnClickListener {
            Toast.makeText(this, "Calm clicked", Toast.LENGTH_SHORT).show()
            onMoodAfterSelected("calm")
        }

        binding.btnMoodSad.setOnClickListener {
            Toast.makeText(this, "Sad clicked", Toast.LENGTH_SHORT).show()
            onMoodAfterSelected("sad")
        }

        binding.btnMoodAngry.setOnClickListener {
            Toast.makeText(this, "Angry clicked", Toast.LENGTH_SHORT).show()
            onMoodAfterSelected("angry")
        }
    }

    private fun applyEmotionTheme(emotion: String) {
        val cleanMood = SpotifyMoodQueryBuilder.normalizeMood(emotion)

        val color: String
        val background: Int

        when (cleanMood) {
            "happy" -> {
                color = "#FFD54F"
                background = R.drawable.bg_history_card_happy
            }

            "sad" -> {
                color = "#5DAEFF"
                background = R.drawable.bg_history_card_sad
            }

            "angry" -> {
                color = "#FF5A5A"
                background = R.drawable.bg_history_card_angry
            }

            "calm" -> {
                color = "#9C7CFF"
                background = R.drawable.bg_history_card_calm
            }

            else -> {
                color = "#7C2CFF"
                background = R.drawable.bg_history_card_neutral
            }
        }

        val parsedColor = Color.parseColor(color)
        val tint = ColorStateList.valueOf(parsedColor)

        binding.tvMoodLabel.setTextColor(parsedColor)
        binding.seekMusic.progressTintList = tint
        binding.seekMusic.thumbTintList = tint
        binding.miniPlayerCard.setBackgroundResource(background)
        binding.miniPlayerStickyTop.setBackgroundResource(background)
        binding.cardForecastHover.setBackgroundResource(background)
    }




    private fun startRecommendationSession(song: Song) {

        val now = System.currentTimeMillis()
        val date = Date(now)

        val displayFormatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        val dayFormatter = SimpleDateFormat("EEEE", Locale.getDefault())
        val hourFormatter = SimpleDateFormat("H", Locale.getDefault())

        val hour = hourFormatter.format(date).toInt()

        val timeOfDay = when (hour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..20 -> "Evening"
            else -> "Night"
        }

        val cleanMood = SpotifyMoodQueryBuilder.normalizeMood(finalMood)

        //
        currentRecommendationSessionId = UUID.randomUUID().toString()

        currentRecommendedSongTitle = song.title
        currentRecommendedArtist = song.artist

        hasMarkedCurrentSessionPlayed = false


        // debug
        Log.d("eMoodtune", "New recommendation session started: $currentRecommendationSessionId")
    }

    private fun markCurrentSessionPlayedIfNeeded() {
        if (currentRecommendationSessionId.isBlank() || hasMarkedCurrentSessionPlayed) return
        // Room implementation for marking session played can be added to ViewModel if needed
        hasMarkedCurrentSessionPlayed = true
    }

    private fun updatePreviousSessionMoodAfterIfNeeded() {
        val previousSessionId = intent.getStringExtra("previous_session_id") ?: return
        val moodAfter = intent.getStringExtra("emotion") ?: return

        if (previousSessionId.isNotBlank() && moodAfter.isNotBlank()) {
            // Room implementation for updating session mood can be added to ViewModel if needed
        }
    }

    private fun handlePlaylistOpenIfNeeded() {
        val openFromPlaylist = intent.getBooleanExtra("open_from_playlist", false)
        if (!openFromPlaylist) return

        val title = intent.getStringExtra("playlist_song_title") ?: return
        val artist = intent.getStringExtra("playlist_song_artist") ?: ""
        val duration = intent.getStringExtra("playlist_song_duration") ?: "0:00"

        intent.removeExtra("open_from_playlist")
        intent.removeExtra("playlist_song_title")
        intent.removeExtra("playlist_song_artist")
        intent.removeExtra("playlist_song_duration")

        val selectedSong = Song(title, artist, duration)

        val existingIndex = currentSongs.indexOfFirst {
            it.title.equals(selectedSong.title, ignoreCase = true) &&
                    it.artist.equals(selectedSong.artist, ignoreCase = true)
        }

        if (existingIndex >= 0) {
            selectSong(existingIndex)
        } else {
            currentSongs = listOf(selectedSong) + originalRecommendedSongs
            selectedSongIndex = 0
            songAdapter.updateSongs(currentSongs, selectedSongIndex)
            selectSong(0)
        }
    }

    private fun maybeAskMoodAfter() {
        if (hasAskedMoodAfter) return
        if (NowPlayingState.activeSessionId.isBlank()) return

        val elapsed = System.currentTimeMillis() - sessionStartTime

        if (elapsed >= 60_000) {
            hasAskedMoodAfter = true
            showMoodPrompt()
        }
    }

    private fun showMoodPrompt() {
        binding.moodAfterContainer.visibility = View.VISIBLE
        binding.moodAfterContainer.alpha = 0f
        binding.moodAfterContainer.translationY = 100f
        binding.moodAfterContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .start()
        
        binding.moodAfterContainer.bringToFront()
        binding.moodAfterContainer.elevation = 20f
    }

    private fun loadForecastPreviewText() {
        try {
            val result = MoodForecastEngine(this).generateForecast()
            val label = "Predicted Mood: ${result.predictedMood}"
            binding.tvForecastPreview.text = label
            binding.tvForecastHoverLabel.text = "Forecast: ${result.predictedMood}"
        } catch (_: Exception) {
            binding.tvForecastPreview.text = "Predicted Mood: ${formatMood(finalMood)}"
            binding.tvForecastHoverLabel.text = "Forecast: ${formatMood(finalMood)}"
        }
    }

    private fun openScanWithSessionContext() {
        val intent = Intent(this, ScanActivity::class.java).apply {
            if (currentRecommendationSessionId.isNotBlank()) {
                putExtra("previous_session_id", currentRecommendationSessionId)
            }
        }
        startActivity(intent)
    }

    private fun startAlbumPulse() {
        stopAlbumPulse()

        val scaleX = ObjectAnimator.ofFloat(binding.imgAlbumArt, "scaleX", 1f, 1.06f, 1f)
        val scaleY = ObjectAnimator.ofFloat(binding.imgAlbumArt, "scaleY", 1f, 1.06f, 1f)
        val alpha = ObjectAnimator.ofFloat(binding.imgAlbumArt, "alpha", 0.90f, 1f, 0.90f)

        scaleX.duration = 1400
        scaleY.duration = 1400
        alpha.duration = 1400

        scaleX.repeatCount = ValueAnimator.INFINITE
        scaleY.repeatCount = ValueAnimator.INFINITE
        alpha.repeatCount = ValueAnimator.INFINITE

        albumPulseAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            start()
        }
    }


    private fun stopAlbumPulse() {
        albumPulseAnimator?.cancel()
        albumPulseAnimator = null
        binding.imgAlbumArt.scaleX = 1f
        binding.imgAlbumArt.scaleY = 1f
        binding.imgAlbumArt.alpha = 0.95f
    }

    private fun updatePlayPauseUI() {
        if (spotifyIsPlaying) {
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            startAlbumPulse()
        } else {
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            stopAlbumPulse()
        }
    }

    private fun loadAlbumArtFromSpotifyRemote(imageUri: com.spotify.protocol.types.ImageUri?) {
        if (imageUri == null || !spotifyPlayerController.isConnected()) return

        spotifyPlayerController.getImage(imageUri) { bitmap ->
            runOnUiThread {
                binding.imgAlbumArt.setImageBitmap(bitmap)
                binding.imgAlbumArt.alpha = 1f
                binding.imgStickyAlbumArt.setImageBitmap(bitmap)
            }
        }
    }

    private fun resolveHistoryMood(): String {
        val current = viewModel.currentMood.value ?: finalMood
        return SpotifyMoodQueryBuilder.normalizeMood(current)
    }

    private fun secondsToTime(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", mins, secs)
    }

    private fun getCurrentDay(): String {
        val calendar = Calendar.getInstance()
        val format = SimpleDateFormat("EEEE", Locale.getDefault())
        return format.format(calendar.time)
    }

    private fun getCurrentTimeOfDay(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..20 -> "Evening"
            else -> "Night"
        }
    }

    private fun formatMood(mood: String): String {
        val cleanMood = SpotifyMoodQueryBuilder.normalizeMood(mood)

        return cleanMood.replace("_", " ").split(" ").joinToString(" ") {
            it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.getDefault()) else c.toString() }
        }
    }

    private fun showSongOptionsDialog(song: Song) {
        val options = arrayOf("Add to Queue", "Add to Playlist")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        QueueManager.addToQueue(song)
                        Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show()
                    }
                    1 -> showAddToPlaylistDialog(song)
                }
            }
            .show()
    }

    private fun showAddToPlaylistDialog(song: Song) {
        val db = AppDatabase.getDatabase(this)
        lifecycleScope.launch(Dispatchers.IO) {
            val playlistList = db.playlistMetadataDao().getAllPlaylists().first()
            withContext(Dispatchers.Main) {
                if (playlistList.isEmpty()) {
                    viewModel.addToPlaylist(song, finalMood, 1)
                    FirebasePlaylistSyncHelper.syncLocalPlaylistToCloud(this@HomeActivity)
                    Toast.makeText(this@HomeActivity, "Added to My Favorites", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                val names = playlistList.map { it.name }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(this@HomeActivity)
                    .setTitle("Choose Playlist")
                    .setItems(names) { _, which ->
                        val selected = playlistList[which]
                        viewModel.addToPlaylist(song, finalMood, selected.id)
                        FirebasePlaylistSyncHelper.syncLocalPlaylistToCloud(this@HomeActivity)
                        Toast.makeText(this@HomeActivity, "Saved to '${selected.name}'", Toast.LENGTH_SHORT).show()
                    }
                    .setNeutralButton("New Playlist") { _, _ ->
                        showCreatePlaylistDialog(song)
                    }
                    .show()
            }
        }
    }

    private fun showCreatePlaylistDialog(song: Song? = null) {
        val input = EditText(this)
        input.hint = "Playlist Name"
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("New Playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = AppDatabase.getDatabase(this@HomeActivity)
                        val id = db.playlistMetadataDao().insert(PlaylistMetadata(name = name))
                        
                        withContext(Dispatchers.Main) {
                            if (song != null) {
                                viewModel.addToPlaylist(song, finalMood, id)
                                Toast.makeText(this@HomeActivity, "Created and added to $name", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@HomeActivity, "Playlist created", Toast.LENGTH_SHORT).show()
                            }
                            FirebasePlaylistSyncHelper.syncLocalPlaylistToCloud(this@HomeActivity)
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun syncHighlightWithCurrentTrack() {
        val nowPlaying = NowPlayingState.currentTrack
        if (nowPlaying != null && currentSongs.isNotEmpty()) {
            val matchIndex = currentSongs.indexOfFirst {
                it.title.equals(nowPlaying.name, ignoreCase = true) &&
                it.artist.equals(nowPlaying.artist, ignoreCase = true)
            }
            if (matchIndex >= 0 && matchIndex != selectedSongIndex) {
                selectedSongIndex = matchIndex
                songAdapter.updateSelectedIndex(selectedSongIndex)
            }
        }
    }

    private fun setupRefreshButton() {
        binding.btnRefreshRecommendations.setOnClickListener {
            refreshRecommendations()
        }
    }

    private fun refreshRecommendations() {
        Toast.makeText(this, "Refreshing recommendations...", Toast.LENGTH_SHORT).show()
        
        // Clear caches to force fresh results
        MoodRecommendationCache.clear(this)
        spotifyTrackCache.clear()
        originalRecommendedSongs = emptyList()
        currentSongs = emptyList()
        songAdapter.updateSongs(emptyList(), 0)
        
        // Trigger reload
        loadEmotionSongs()
    }
}
