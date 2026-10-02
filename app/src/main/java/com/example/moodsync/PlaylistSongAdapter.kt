package com.example.moodsync

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.net.URL
import java.util.concurrent.Executors

@androidx.camera.core.ExperimentalGetImage
class PlaylistSongAdapter(
    private var songs: List<PlaylistItem>,
    private val onClick: (PlaylistItem) -> Unit,
    private val onLongClick: (PlaylistItem) -> Unit
) : RecyclerView.Adapter<PlaylistSongAdapter.PlaylistSongViewHolder>() {

    private val imageExecutor = Executors.newFixedThreadPool(3)

    fun updateSongs(newSongs: List<PlaylistItem>) {
        songs = newSongs
        notifyDataSetChanged()
    }

    inner class PlaylistSongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgPlaylistAlbum: ImageView? = view.findViewById(R.id.imgPlaylistAlbum)
        val tvPlaylistSongTitle: TextView = view.findViewById(R.id.tvPlaylistSongTitle)
        val tvPlaylistEmotion: TextView = view.findViewById(R.id.tvPlaylistEmotion)
        val tvPlaylistSongArtist: TextView = view.findViewById(R.id.tvPlaylistSongArtist)
        val tvPlaylistSongDuration: TextView = view.findViewById(R.id.tvPlaylistSongDuration)
        val tvQueuedBadge: View = view.findViewById(R.id.tvPlaylistQueuedBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistSongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_song, parent, false)
        return PlaylistSongViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaylistSongViewHolder, position: Int) {
        val item = songs[position]
        val card = holder.itemView.findViewById<View>(R.id.playlistCardRoot)

        val bgRes = when (item.emotion.trim().lowercase()) {
            "happy" -> R.drawable.bg_history_card_happy
            "sad" -> R.drawable.bg_history_card_sad
            "angry" -> R.drawable.bg_history_card_angry
            "calm" -> R.drawable.bg_history_card_calm
            else -> R.drawable.bg_history_card_neutral
        }

        card.setBackgroundResource(bgRes)
        holder.tvPlaylistSongTitle.text = item.title.ifBlank { "Unknown Song" }
        holder.tvPlaylistEmotion.text = item.emotion.ifBlank { "Mixed" }
        holder.tvPlaylistSongArtist.text = item.artist.ifBlank { "Unknown Artist" }
        holder.tvPlaylistSongDuration.text = item.duration.ifBlank { "0:00" }

        // Queue Indicator
        val isInQueue = QueueManager.queue.value?.any { 
            it.title.equals(item.title, ignoreCase = true) && it.artist.equals(item.artist, ignoreCase = true) 
        } ?: false
        holder.tvQueuedBadge.visibility = if (isInQueue) View.VISIBLE else View.GONE

        holder.imgPlaylistAlbum?.let { imageView ->
            imageView.setImageResource(R.drawable.emoodtune_logo)
            loadSpotifyAlbumArt(imageView, item.title, item.artist)
        }

        holder.itemView.setOnClickListener {
            onClick(item)
        }

        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    override fun getItemCount(): Int = songs.size

    private fun loadSpotifyAlbumArt(
        imageView: ImageView,
        title: String,
        artist: String
    ) {
        val context = imageView.context
        val key = SpotifyArtworkCache.makeKey(title, artist)

        val cachedUrl = SpotifyArtworkCache.get(key)
        if (!cachedUrl.isNullOrBlank()) {
            loadImageIntoView(imageView, cachedUrl)
            return
        }

        imageExecutor.execute {
            val token = SpotifySessionManager.getValidAccessToken(context)
            if (token.isNullOrBlank()) return@execute

            try {
                val result = SpotifyRepository.searchBestTrack(
                    accessToken = token,
                    songTitle = title,
                    artist = artist
                )

                val imageUrl = result?.albumImageUrl ?: ""
                if (imageUrl.isNotBlank()) {
                    SpotifyArtworkCache.put(key, imageUrl)
                    imageView.post {
                        loadImageIntoView(imageView, imageUrl)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun loadImageIntoView(imageView: ImageView, imageUrl: String) {
        imageExecutor.execute {
            try {
                val bitmap = URL(imageUrl).openStream().use {
                    BitmapFactory.decodeStream(it)
                }
                imageView.post {
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}
