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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@androidx.camera.core.ExperimentalGetImage
class HistoryAdapter(
    private var items: List<HistoryItem>,
    private val onClick: (HistoryItem) -> Unit,
    private val onLongClick: ((HistoryItem) -> Unit)? = null
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    inner class HistoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvGroupHeader: TextView = view.findViewById(R.id.tvGroupHeader)
        val imgHistoryAlbum: ImageView = view.findViewById(R.id.imgHistoryAlbum)
        val tvHistorySong: TextView = view.findViewById(R.id.tvHistorySong)
        val tvHistoryArtist: TextView = view.findViewById(R.id.tvHistoryArtist)
        val tvHistoryTimestamp: TextView = view.findViewById(R.id.tvHistoryTimestamp)
        val tvHistoryFeedback: TextView = view.findViewById(R.id.tvHistoryFeedback)
        val tvHistoryDuration: TextView = view.findViewById(R.id.tvHistoryDuration)
        val tvHistoryEmotion: TextView = view.findViewById(R.id.tvHistoryEmotion)
        val btnHistoryMore: ImageButton = view.findViewById(R.id.btnHistoryMore)
        val tvQueuedBadge: View = view.findViewById(R.id.tvHistoryQueuedBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val item = items[position]

        val currentGroup = getGroupLabel(item)
        val previousGroup = items.getOrNull(position - 1)?.let { getGroupLabel(it) }
        val card = holder.itemView.findViewById<View>(R.id.historyCardRoot)

        val bgRes = when (item.emotion.trim().lowercase()) {
            "happy" -> R.drawable.bg_history_card_happy
            "sad" -> R.drawable.bg_history_card_sad
            "angry" -> R.drawable.bg_history_card_angry
            "calm" -> R.drawable.bg_history_card_calm
            else -> R.drawable.bg_history_card_neutral
        }

        card.setBackgroundResource(bgRes)

        holder.tvGroupHeader.visibility =
            if (position == 0 || currentGroup != previousGroup) View.VISIBLE else View.GONE
        holder.tvGroupHeader.text = currentGroup

        holder.tvHistorySong.text = item.songTitle.ifBlank { "Unknown Song" }
        holder.tvHistoryArtist.text = item.artist.ifBlank { "Unknown Artist" }
        holder.tvHistoryTimestamp.text = buildTimestampText(item)
        holder.tvHistoryDuration.text = item.duration.ifBlank { "0:00" }
        holder.tvHistoryEmotion.text = formatMood(item.emotion.ifBlank { "neutral" })
        holder.tvHistoryFeedback.text = buildFeedbackText(item)

        // Queue Indicator
        val isInQueue = QueueManager.queue.value?.any { 
            it.title.equals(item.songTitle, ignoreCase = true) && it.artist.equals(item.artist, ignoreCase = true) 
        } ?: false
        holder.tvQueuedBadge.visibility = if (isInQueue) View.VISIBLE else View.GONE

        holder.imgHistoryAlbum.setImageResource(R.drawable.emoodtune_logo)
        loadSpotifyAlbumArt(holder, item.songTitle, item.artist)

        holder.itemView.setOnClickListener {
            onClick(item)
        }

        holder.itemView.setOnLongClickListener {
            onLongClick?.invoke(item)
            true
        }

        holder.btnHistoryMore.setOnClickListener {
            onLongClick?.invoke(item)
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<HistoryItem>) {
        items = newItems.sortedByDescending { it.epochMillis }
        notifyDataSetChanged()
    }

    private fun buildTimestampText(item: HistoryItem): String {
        val parts = mutableListOf<String>()

        if (item.timestamp.isNotBlank()) parts.add(item.timestamp)
        if (item.timeOfDay.isNotBlank()) parts.add(item.timeOfDay)
        if (item.source.isNotBlank()) {
            parts.add(
                item.source.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }
            )
        }

        return if (parts.isEmpty()) "Unknown time" else parts.joinToString(" • ")
    }

    private fun formatMood(mood: String): String {
        return mood.replace("_", " ").split(" ").joinToString(" ") {
            it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.getDefault()) else c.toString() }
        }
    }

    private fun buildFeedbackText(item: HistoryItem): String {
        val parts = mutableListOf<String>()

        if (item.wasPlayed) parts.add("Played")
        if (item.wasSkipped) parts.add("Skipped")
        if (item.wasReplayed) parts.add("Replayed")

        if (item.moodBefore.isNotBlank() && item.moodAfter.isNotBlank()) {
            parts.add(
                "${formatMood(item.moodBefore)} → ${formatMood(item.moodAfter)}"
            )
        }

        if (parts.isEmpty()) {
            parts.add(
                item.source.ifBlank { "history" }.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }
            )
        }

        return parts.joinToString(" • ")
    }

    private fun getGroupLabel(item: HistoryItem): String {
        val itemCal = Calendar.getInstance().apply {
            timeInMillis = item.epochMillis
        }

        val todayCal = Calendar.getInstance()
        val yesterdayCal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }

        return when {
            isSameDay(itemCal, todayCal) -> "Today"
            isSameDay(itemCal, yesterdayCal) -> "Yesterday"
            else -> {
                val formatter = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                formatter.format(Date(item.epochMillis))
            }
        }
    }

    private fun isSameDay(first: Calendar, second: Calendar): Boolean {
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
                first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
    }

    private fun loadSpotifyAlbumArt(
        holder: HistoryViewHolder,
        title: String,
        artist: String
    ) {
        val context = holder.itemView.context
        val key = SpotifyArtworkCache.makeKey(title, artist)

        val cachedUrl = SpotifyArtworkCache.get(key)
        if (!cachedUrl.isNullOrBlank()) {
            loadImageIntoView(holder.imgHistoryAlbum, cachedUrl)
            return
        }

        Thread {
            val token = SpotifySessionManager.getValidAccessToken(context)
            if (token.isNullOrBlank()) return@Thread

            try {
                val result = SpotifyRepository.searchBestTrack(
                    accessToken = token,
                    songTitle = title,
                    artist = artist
                )

                val imageUrl = result?.albumImageUrl ?: ""
                if (imageUrl.isNotBlank()) {
                    SpotifyArtworkCache.put(key, imageUrl)
                    holder.itemView.post {
                        loadImageIntoView(holder.imgHistoryAlbum, imageUrl)
                    }
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun loadImageIntoView(imageView: ImageView, imageUrl: String) {
        Thread {
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
        }.start()
    }
}