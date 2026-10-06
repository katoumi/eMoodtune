package com.example.moodsync

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.moodsync.databinding.ActivityPlaylistBinding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.camera.core.ExperimentalGetImage
class PlaylistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistBinding
    private val viewModel: PlaylistViewModel by lazy {
        ViewModelProvider(this)[PlaylistViewModel::class.java]
    }

    private lateinit var adapter: PlaylistSongAdapter
    private lateinit var tabAdapter: PlaylistTabAdapter
    private lateinit var mixAdapter: MoodMixAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NotificationNavHelper.setup(this)

        setupTopTabs()
        setupBottomNav()
        setupProfileButton()
        setupClearPlaylistButton()
        setupPlayAllButton()
        setupAddPlaylistButton()
        setupRecycler()
        setupObservers()
    }

    override fun onResume() {
        super.onResume()
        ProfileImageLoader.load(binding.imgProfile)
    }

    private fun setupObservers() {
        viewModel.playlists.observe(this) { list ->
            tabAdapter.updatePlaylists(list, viewModel.selectedPlaylistId.value ?: -1L)
        }

        viewModel.selectedPlaylistId.observe(this) { id ->
            if (id != null) {
                val list = viewModel.playlists.value ?: emptyList()
                tabAdapter.updatePlaylists(list, id)
                val currentPlaylist = list.find { it.id == id }
                binding.tvPlaylistTitle.text = currentPlaylist?.name ?: "Your Playlists"
                
                // Clear mood selection visually
                mixAdapter.updateItems(viewModel.groupedMoodMixes.value ?: emptyList(), null)
            }
        }

        viewModel.selectedMoodName.observe(this) { mood ->
            if (mood != null) {
                binding.tvPlaylistTitle.text = "${SpotifyMoodQueryBuilder.displayMood(mood)} Mix"
                mixAdapter.updateItems(viewModel.groupedMoodMixes.value ?: emptyList(), mood)
                
                // Clear manual playlist selection visually
                tabAdapter.updatePlaylists(viewModel.playlists.value ?: emptyList(), -1L)
            }
        }

        viewModel.playlistItems.observe(this) { songs ->
            adapter.updateSongs(songs)

            binding.tvEmptyPlaylist.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
            binding.btnClearPlaylist.visibility = if (songs.isEmpty()) View.GONE else View.VISIBLE
            binding.btnPlayAll.visibility = if (songs.isEmpty()) View.GONE else View.VISIBLE
            binding.btnShufflePlay.visibility = if (songs.isEmpty()) View.GONE else View.VISIBLE
            binding.recyclerPlaylist.visibility = if (songs.isEmpty()) View.GONE else View.VISIBLE

            binding.tvPlaylistInsight.text = if (songs.isEmpty()) {
                "No saved songs yet"
            } else {
                val topMood = songs.groupingBy { it.emotion.trim() }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key ?: "Mixed"
                "Saved songs: ${songs.size} • Most saved mood: $topMood"
            }
        }

        viewModel.groupedMoodMixes.observe(this) { mixes ->
            mixAdapter.updateItems(mixes, viewModel.selectedMoodName.value)
            binding.recyclerMoodMixes.visibility = if (mixes.isEmpty()) View.GONE else View.VISIBLE
        }

        QueueManager.queue.observe(this) { list ->
            // Refresh list to update "Queued" badges
            val currentSongs = viewModel.playlistItems.value ?: emptyList()
            adapter.updateSongs(currentSongs)

            // Update Tab Label with count
            val queueSize = list.size
            binding.tabQueue.text = if (queueSize > 0) "Queue ($queueSize)" else "Queue"
        }
    }

    private fun setupRecycler() {
        tabAdapter = PlaylistTabAdapter(
            playlists = emptyList(),
            selectedId = 1,
            onTabClick = { playlist ->
                viewModel.selectPlaylist(playlist.id)
            },
            onTabLongClick = { playlist ->
                if (playlist.id != 1L) { // Prevent editing "My Favorites"
                    showPlaylistOptionsDialog(playlist)
                }
            }
        )
        binding.recyclerPlaylistTabs.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerPlaylistTabs.adapter = tabAdapter

        adapter = PlaylistSongAdapter(
            songs = emptyList(),
            onClick = { selectedSong ->
                playSong(selectedSong)
            },
            onLongClick = { selectedSong ->
                showSongOptionsDialog(selectedSong)
            }
        )
        binding.recyclerPlaylist.layoutManager = LinearLayoutManager(this)
        binding.recyclerPlaylist.adapter = adapter

        mixAdapter = MoodMixAdapter(emptyList(), null) { mix ->
            viewModel.selectMoodMix(mix.mood)
            
            // SYNC: Update global app mood to match the selected mix
            getSharedPreferences("moodsync_home_state", MODE_PRIVATE).edit()
                .putString("last_emotion", mix.mood)
                .putString("last_final_mood", mix.mood)
                .putBoolean("is_manual_mood", true)
                .apply()
        }
        binding.recyclerMoodMixes.adapter = mixAdapter
    }

    private fun playPlaylistSong(song: HomeActivity.Song) {
        val intent = Intent(this, HomeActivity::class.java).apply {
            putExtra("playlist_song_title", song.title)
            putExtra("playlist_song_artist", song.artist)
            putExtra("playlist_song_duration", song.duration)
            putExtra("open_from_playlist", true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun playSong(song: PlaylistItem) {
        playPlaylistSong(HomeActivity.Song(song.title, song.artist, song.duration))
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

        binding.tabPlaylist.setOnClickListener { }

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
        }
    }

    private fun setupClearPlaylistButton() {
        binding.btnClearPlaylist.setOnClickListener {
            showClearPlaylistDialog()
        }
    }

    private fun setupPlayAllButton() {
        binding.btnPlayAll.setOnClickListener {
            playPlaylist(shuffle = false)
        }

        binding.btnShufflePlay.setOnClickListener {
            playPlaylist(shuffle = true)
        }
    }

    private fun playPlaylist(shuffle: Boolean) {
        val songs = viewModel.playlistItems.value
        if (songs.isNullOrEmpty()) {
            Toast.makeText(this, "Playlist is empty", Toast.LENGTH_SHORT).show()
            return
        }

        val queueList = songs.map { 
            HomeActivity.Song(it.title, it.artist, it.duration)
        }

        val finalQueue = if (shuffle) queueList.shuffled() else queueList
        
        QueueManager.replaceAll(finalQueue)

        // SYNC MOOD: If a mix is selected, sync the global app mood
        viewModel.selectedMoodName.value?.let { mood ->
            getSharedPreferences("moodsync_home_state", MODE_PRIVATE).edit()
                .putString("last_emotion", mood)
                .putString("last_final_mood", mood)
                .putBoolean("is_manual_mood", true)
                .apply()
        }
        
        // BUG FIX: Consume the first song from the queue immediately
        val firstSong = QueueManager.playNext()
        if (firstSong != null) {
            playPlaylistSong(firstSong)
        }
        
        Toast.makeText(this, if (shuffle) "Shuffling playlist..." else "Playing all", Toast.LENGTH_SHORT).show()
    }

    private fun showClearPlaylistDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear Playlist")
            .setMessage("Are you sure you want to remove all songs from your playlist?")
            .setPositiveButton("Yes") { _, _ ->
                viewModel.clearPlaylist()
                Toast.makeText(this, "Playlist cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupAddPlaylistButton() {
        binding.btnAddPlaylist.setOnClickListener {
            showCreatePlaylistDialog()
        }
    }

    private fun showCreatePlaylistDialog() {
        val input = android.widget.EditText(this)
        input.hint = "Playlist Name"
        AlertDialog.Builder(this)
            .setTitle("New Playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.createPlaylist(name)
                    FirebasePlaylistSyncHelper.syncLocalPlaylistToCloud(this)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPlaylistOptionsDialog(playlist: PlaylistMetadata) {
        val options = arrayOf("Rename", "Delete")
        AlertDialog.Builder(this)
            .setTitle(playlist.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenamePlaylistDialog(playlist)
                    1 -> showDeletePlaylistConfirmation(playlist)
                }
            }
            .show()
    }

    private fun showRenamePlaylistDialog(playlist: PlaylistMetadata) {
        val input = android.widget.EditText(this)
        input.setText(playlist.name)
        AlertDialog.Builder(this)
            .setTitle("Rename Playlist")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.renamePlaylist(playlist.id, name)
                    FirebasePlaylistSyncHelper.syncLocalPlaylistToCloud(this)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeletePlaylistConfirmation(playlist: PlaylistMetadata) {
        AlertDialog.Builder(this)
            .setTitle("Delete Playlist")
            .setMessage("Are you sure you want to delete '${playlist.name}' and all its songs?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deletePlaylist(playlist)
                FirebasePlaylistSyncHelper.syncLocalPlaylistToCloud(this)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSongOptionsDialog(song: PlaylistItem) {
        val options = arrayOf("Play", "Add to Queue", "Copy to Playlist", "Remove")
        AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> playSong(song)
                    1 -> {
                        QueueManager.addToQueue(HomeActivity.Song(song.title, song.artist, song.duration))
                        Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show()
                    }
                    2 -> showCopySongToPlaylistDialog(song)
                    3 -> {
                        viewModel.removeSong(song)
                        FirebasePlaylistSyncHelper.syncLocalPlaylistToCloud(this)
                        Toast.makeText(this, "Removed from playlist", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun showCopySongToPlaylistDialog(song: PlaylistItem) {
        val playlistList = viewModel.playlists.value ?: emptyList()
        val names = playlistList.map { it.name }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("Copy Song to")
            .setItems(names) { _, which ->
                val selected = playlistList[which]
                val newItem = song.copy(id = 0, playlistId = selected.id)
                // Use a quick DB operation
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(this@PlaylistActivity)
                    db.playlistDao().insert(newItem)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PlaylistActivity, "Copied to ${selected.name}", Toast.LENGTH_SHORT).show()
                        FirebasePlaylistSyncHelper.syncLocalPlaylistToCloud(this@PlaylistActivity)
                    }
                }
            }
            .show()
    }
}
