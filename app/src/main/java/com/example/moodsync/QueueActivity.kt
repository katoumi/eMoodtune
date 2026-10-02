package com.example.moodsync

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.moodsync.databinding.ActivityQueueBinding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

@androidx.camera.core.ExperimentalGetImage
class QueueActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQueueBinding
    private lateinit var adapter: RecommendedSongAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQueueBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NotificationNavHelper.setup(this)

        setupRecycler()
        setupTopTabs()
        setupBottomNav()
        setupProfileButton()
        setupClearButton()
        setupObservers()
    }

    override fun onResume() {
        super.onResume()
        ProfileImageLoader.load(binding.imgProfile)
    }

    private fun setupObservers() {
        QueueManager.queue.observe(this) { list ->
            adapter.updateSongs(list, -1)
            binding.tvEmptyQueue.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerQueue.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE

            // Update Tab Label with count
            val queueSize = list.size
            binding.tabQueue.text = if (queueSize > 0) "Queue ($queueSize)" else "Queue"
        }
    }

    private fun setupRecycler() {
        adapter = RecommendedSongAdapter(
            songs = emptyList(),
            selectedIndex = -1,
            onSongClick = { index ->
                val song = QueueManager.queue.value?.get(index)
                if (song != null) {
                    playSong(song)
                    QueueManager.removeFromQueue(song)
                }
            },
            onAddClick = { song ->
                // Maybe "Add to Playlist" from queue?
                Toast.makeText(this, "Song already in queue", Toast.LENGTH_SHORT).show()
            },
            onLongClick = { song ->
                showQueueOptions(song)
            }
        )
        binding.recyclerQueue.layoutManager = LinearLayoutManager(this)
        binding.recyclerQueue.adapter = adapter
    }

    private fun showQueueOptions(song: HomeActivity.Song) {
        val options = arrayOf("Play Now", "Remove from Queue", "Add to Playlist")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        playSong(song)
                        QueueManager.removeFromQueue(song)
                    }
                    1 -> {
                        QueueManager.removeFromQueue(song)
                        Toast.makeText(this, "Removed from queue", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        showAddToPlaylistDialog(song)
                    }
                }
            }
            .show()
    }

    private fun showAddToPlaylistDialog(song: HomeActivity.Song) {
        val db = AppDatabase.getDatabase(this)
        lifecycleScope.launch(Dispatchers.IO) {
            val playlists = db.playlistMetadataDao().getAllPlaylists().first()
            withContext(Dispatchers.Main) {
                if (playlists.isEmpty()) {
                    saveToPlaylist(song, 1)
                    return@withContext
                }

                val names = playlists.map { it.name }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(this@QueueActivity)
                    .setTitle("Choose Playlist")
                    .setItems(names) { _, which ->
                        val selected = playlists[which]
                        saveToPlaylist(song, selected.id, selected.name)
                    }
                    .setNeutralButton("New Playlist") { _, _ ->
                        showCreatePlaylistDialog(song)
                    }
                    .show()
            }
        }
    }

    private fun saveToPlaylist(song: HomeActivity.Song, playlistId: Long, playlistName: String = "") {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@QueueActivity)
            db.playlistDao().insert(
                PlaylistItem(
                    title = song.title,
                    artist = song.artist,
                    duration = song.duration,
                    emotion = "queued",
                    playlistId = playlistId
                )
            )
            withContext(Dispatchers.Main) {
                val msg = if (playlistName.isNotEmpty()) "Saved to '$playlistName'" else "Added to playlist"
                Toast.makeText(this@QueueActivity, msg, Toast.LENGTH_SHORT).show()
                FirebasePlaylistSyncHelper.syncLocalPlaylistToCloud(this@QueueActivity)
            }
        }
    }

    private fun showCreatePlaylistDialog(song: HomeActivity.Song) {
        val input = android.widget.EditText(this)
        input.hint = "Playlist Name"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("New Playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = AppDatabase.getDatabase(this@QueueActivity)
                        val id = db.playlistMetadataDao().insert(PlaylistMetadata(name = name))
                        saveToPlaylist(song, id)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun playSong(song: HomeActivity.Song) {
        val intent = Intent(this, HomeActivity::class.java).apply {
            putExtra("playlist_song_title", song.title)
            putExtra("playlist_song_artist", song.artist)
            putExtra("playlist_song_duration", song.duration)
            putExtra("open_from_playlist", true)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
    }

    private fun setupTopTabs() {
        binding.tabHome.setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }

        binding.tabHistory.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }

        binding.tabPlaylist.setOnClickListener {
            val intent = Intent(this, PlaylistActivity::class.java)
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
            val intent = Intent(this, SettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }
    }

    private fun setupClearButton() {
        binding.btnShuffleQueue.setOnClickListener {
            QueueManager.shuffle()
            Toast.makeText(this, "Queue shuffled", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearQueue.setOnClickListener {
            QueueManager.clearQueue()
            Toast.makeText(this, "Queue cleared", Toast.LENGTH_SHORT).show()
        }
    }
}
