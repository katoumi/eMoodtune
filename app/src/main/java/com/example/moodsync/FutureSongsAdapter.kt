package com.example.moodsync

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FutureSongsAdapter(
    private val onClick: (FutureRecommendationItem) -> Unit
) : RecyclerView.Adapter<FutureSongsAdapter.FutureSongViewHolder>() {

    private val items = mutableListOf<FutureRecommendationItem>()

    fun submitList(newItems: List<FutureRecommendationItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FutureSongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_future_song, parent, false)
        return FutureSongViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: FutureSongViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class FutureSongViewHolder(
        itemView: View,
        private val onClick: (FutureRecommendationItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvFutureSongTitle: TextView =
            itemView.findViewById(R.id.tvFutureSongTitle)

        private val tvFutureSongMeta: TextView =
            itemView.findViewById(R.id.tvFutureSongMeta)

        fun bind(item: FutureRecommendationItem) {
            tvFutureSongTitle.text = "${item.songTitle} - ${item.artist}"
            tvFutureSongMeta.text =
                "${item.timelineLabel} • ${capitalize(item.predictedMood)} • ${item.confidence}% • Spotify"

            itemView.setOnClickListener {
                onClick(item)
            }
        }

        private fun capitalize(text: String): String {
            return text.replace("_", " ").split(" ").joinToString(" ") {
                it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }
            }
        }
    }
}