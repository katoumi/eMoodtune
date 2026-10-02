package com.example.moodsync

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.moodsync.databinding.ActivityHistoryBinding
import java.util.Locale
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

@androidx.camera.core.ExperimentalGetImage
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val viewModel: HistoryViewModel by lazy {
        ViewModelProvider(this)[HistoryViewModel::class.java]
    }

    private lateinit var historyAdapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecycler()
        setupTopTabs()
        setupBottomNav()
        setupProfileButton()
        setupClearHistory()
        setupSearchBar()
        setupObservers()
    }

    override fun onResume() {
        super.onResume()
        ProfileImageLoader.load(binding.imgProfile)
    }

    private fun setupObservers() {
        viewModel.historyItems.observe(this) { historyItems ->
            // Show all history items to ensure transparency and consistency
            historyAdapter.updateItems(historyItems)

            binding.tvEmptyHistory.visibility = if (historyItems.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerHistory.visibility = if (historyItems.isEmpty()) View.GONE else View.VISIBLE
            binding.tvClearHistory.visibility = if (historyItems.isEmpty()) View.GONE else View.VISIBLE

            binding.tvHistoryInsight.text = generateInsight(historyItems)
        }

        QueueManager.queue.observe(this) { list ->
            // Refresh list to update "Queued" badges
            val currentItems = viewModel.historyItems.value ?: emptyList()
            historyAdapter.updateItems(currentItems)

            // Update Tab Label with count
            val queueSize = list.size
            binding.tabQueue.text = if (queueSize > 0) "Queue ($queueSize)" else "Queue"
        }
    }

    private fun setupSearchBar() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.updateSearchQuery(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun setupRecycler() {
        historyAdapter = HistoryAdapter(
            items = emptyList(),
            onClick = { item ->
                val intent = Intent(this, HomeActivity::class.java).apply {
                    putExtra("playlist_song_title", item.songTitle)
                    putExtra("playlist_song_artist", item.artist)
                    putExtra("playlist_song_duration", item.duration)
                    putExtra("open_from_playlist", true)

                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }

                startActivity(intent)
                finish()
            },
            onLongClick = { item ->
                showSongOptionsDialog(item)
            }
        )

        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = historyAdapter
    }

    private fun generateInsight(list: List<HistoryItem>): String {
        if (list.isEmpty()) return "No listening data yet"

        val mostMood = list.groupingBy { it.emotion.trim().lowercase(Locale.getDefault()) }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            } ?: "Neutral"

        val mostTime = list.groupingBy { it.timeOfDay.trim() }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: "Anytime"

        val topArtist = list.groupingBy { it.artist.trim() }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: "Unknown"

        return "Most common mood: $mostMood • Most active: $mostTime • Top artist: $topArtist"
    }

    private fun showSongOptionsDialog(item: HistoryItem) {
        val options = arrayOf("Add to Queue", "Add to Playlist")
        AlertDialog.Builder(this)
            .setTitle(item.songTitle)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        QueueManager.addToQueue(HomeActivity.Song(item.songTitle, item.artist, item.duration))
                        Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show()
                    }
                    1 -> showAddToPlaylistDialog(item)
                }
            }
            .show()
    }

    private fun showAddToPlaylistDialog(item: HistoryItem) {
        // We need playlists. HistoryViewModel doesn't have it yet.
        // Let's use a quick DB fetch or update HistoryViewModel.
        // For simplicity and speed, let's update HistoryViewModel.
        
        val db = AppDatabase.getDatabase(this)
        lifecycleScope.launch(Dispatchers.IO) {
            val playlists = db.playlistMetadataDao().getAllPlaylists().first()
            withContext(Dispatchers.Main) {
                if (playlists.isEmpty()) {
                    addToPlaylist(item, 1)
                    return@withContext
                }

                val names = playlists.map { it.name }.toTypedArray()
                AlertDialog.Builder(this@HistoryActivity)
                    .setTitle("Choose Playlist")
                    .setItems(names) { _, which ->
                        val selected = playlists[which]
                        addToPlaylist(item, selected.id, selected.name)
                    }
                    .setNeutralButton("New Playlist") { _, _ ->
                        showCreatePlaylistDialog(item)
                    }
                    .show()
            }
        }
    }

    private fun addToPlaylist(item: HistoryItem, playlistId: Long, playlistName: String = "") {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@HistoryActivity)
            db.playlistDao().insert(
                PlaylistItem(
                    title = item.songTitle,
                    artist = item.artist,
                    duration = item.duration,
                    emotion = item.emotion,
                    playlistId = playlistId
                )
            )
            withContext(Dispatchers.Main) {
                val msg = if (playlistName.isNotEmpty()) "Saved to '$playlistName'" else "Added to playlist"
                Toast.makeText(this@HistoryActivity, msg, Toast.LENGTH_SHORT).show()
                FirebasePlaylistSyncHelper.syncLocalPlaylistToCloud(this@HistoryActivity)
            }
        }
    }

    private fun showCreatePlaylistDialog(item: HistoryItem) {
        val input = android.widget.EditText(this)
        input.hint = "Playlist Name"
        AlertDialog.Builder(this)
            .setTitle("New Playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = AppDatabase.getDatabase(this@HistoryActivity)
                        val id = db.playlistMetadataDao().insert(PlaylistMetadata(name = name))
                        addToPlaylist(item, id)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupClearHistory() {
        binding.tvClearHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear History")
                .setMessage("Are you sure you want to remove all history entries?")
                .setPositiveButton("Yes") { _, _ ->
                    viewModel.clearHistory()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupTopTabs() {
        binding.tabHome.setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java)
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
            val intent = Intent(this, HomeActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }

        binding.navScan.setOnClickListener {
            val intent = Intent(this, ScanActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }

        binding.navProfile.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }
    }

    private fun setupProfileButton() {
        binding.imgProfile.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
        }
    }
}
