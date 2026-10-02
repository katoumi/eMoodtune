package com.example.moodsync

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RecommendedSongAdapter(
    private var songs: List<HomeActivity.Song>,
    private var selectedIndex: Int,
    private val onSongClick: (Int) -> Unit,
    private val onAddClick: (HomeActivity.Song) -> Unit,
    private val onQueueClick: ((HomeActivity.Song) -> Unit)? = null,
    private val onLongClick: ((HomeActivity.Song) -> Unit)? = null
) : RecyclerView.Adapter<RecommendedSongAdapter.SongViewHolder>() {

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val songCardRoot: View = itemView.findViewById(R.id.songCardRoot)
        val tvSongTitle: TextView = itemView.findViewById(R.id.tvSongTitle)
        val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        val btnAdd: ImageButton = itemView.findViewById(R.id.btnAdd)
        val btnQueue: ImageButton = itemView.findViewById(R.id.btnQueue)
        val tvReason: TextView = itemView.findViewById(R.id.tvReason)
        val tvQueuedBadge: View = itemView.findViewById(R.id.tvQueuedBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recommended_song, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = songs[position]

        holder.tvSongTitle.text = song.title
        holder.tvArtist.text = song.artist
        holder.tvDuration.text = song.duration
        holder.tvReason.text = song.reason

        // Queue Indicator
        val isInQueue = QueueManager.queue.value?.any { 
            it.title.equals(song.title, ignoreCase = true) && it.artist.equals(song.artist, ignoreCase = true) 
        } ?: false
        holder.tvQueuedBadge.visibility = if (isInQueue) View.VISIBLE else View.GONE

        if (position == selectedIndex) {
            holder.songCardRoot.setBackgroundResource(R.drawable.bg_song_card_selected)
            holder.songCardRoot.alpha = 1.0f
        } else {
            holder.songCardRoot.setBackgroundResource(R.drawable.bg_song_card)
            holder.songCardRoot.alpha = 0.75f
        }

        holder.songCardRoot.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onSongClick(pos)
            }
        }

        holder.songCardRoot.setOnLongClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onLongClick?.invoke(songs[pos])
            }
            true
        }

        holder.btnAdd.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onAddClick(songs[pos])
            }
        }

        holder.btnQueue.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onQueueClick?.invoke(songs[pos])
            }
        }
    }

    override fun getItemCount(): Int = songs.size

    fun updateSongs(newSongs: List<HomeActivity.Song>, newSelectedIndex: Int) {
        songs = newSongs
        selectedIndex = newSelectedIndex
        notifyDataSetChanged()
    }

    fun updateSelectedIndex(newSelectedIndex: Int) {
        val oldIndex = selectedIndex
        selectedIndex = newSelectedIndex

        if (oldIndex != RecyclerView.NO_POSITION && oldIndex in songs.indices) {
            notifyItemChanged(oldIndex)
        }
        if (newSelectedIndex in songs.indices) {
            notifyItemChanged(newSelectedIndex)
        }
    }
}