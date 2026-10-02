package com.example.moodsync

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MoodMixAdapter(
    private var items: List<MoodMix>,
    private var selectedMood: String? = null,
    private val onClick: (MoodMix) -> Unit
) : RecyclerView.Adapter<MoodMixAdapter.MixViewHolder>() {

    inner class MixViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val mixCardRoot: View = view.findViewById(R.id.mixCardRoot)
        val tvMixMood: TextView = view.findViewById(R.id.tvMixMood)
        val tvMixLabel: TextView = view.findViewById(R.id.tvMixLabel)
        val tvMixStats: TextView = view.findViewById(R.id.tvMixStats)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MixViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_mood_mix, parent, false)
        return MixViewHolder(view)
    }

    override fun onBindViewHolder(holder: MixViewHolder, position: Int) {
        val item = items[position]
        val isSelected = item.mood.equals(selectedMood, ignoreCase = true)
        
        holder.tvMixLabel.text = "${item.mood.replaceFirstChar { it.uppercase() }} Mix"
        holder.tvMixMood.text = item.mood.uppercase()
        holder.tvMixStats.text = "${item.songs.size} songs"
        
        if (isSelected) {
            holder.mixCardRoot.setBackgroundResource(R.drawable.bg_chip_selected)
        } else {
            holder.mixCardRoot.setBackgroundResource(R.drawable.bg_player_mini_card_figma)
        }

        holder.itemView.setOnClickListener { onClick(item) }
        
        val color = when(item.mood.lowercase()) {
            "happy" -> "#FFD54F"
            "sad" -> "#5DAEFF"
            "angry" -> "#FF5A5A"
            "calm" -> "#9C7CFF"
            else -> "#C992FF"
        }
        holder.tvMixMood.setTextColor(android.graphics.Color.parseColor(color))
    }

    override fun getItemCount() = items.size

    fun updateItems(newList: List<MoodMix>, selected: String?) {
        items = newList
        selectedMood = selected
        notifyDataSetChanged()
    }
}
