package com.example.moodsync

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PlaylistTabAdapter(
    private var playlists: List<PlaylistMetadata>,
    private var selectedId: Long,
    private val onTabClick: (PlaylistMetadata) -> Unit,
    private val onTabLongClick: (PlaylistMetadata) -> Unit
) : RecyclerView.Adapter<PlaylistTabAdapter.TabViewHolder>() {

    inner class TabViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTabName: TextView = view.findViewById(R.id.tvTabName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_tab, parent, false)
        return TabViewHolder(view)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        val playlist = playlists[position]
        holder.tvTabName.text = playlist.name

        if (playlist.id == selectedId) {
            holder.tvTabName.setBackgroundResource(R.drawable.bg_chip_selected)
            holder.tvTabName.setTextColor(android.graphics.Color.WHITE)
        } else {
            holder.tvTabName.setBackgroundResource(R.drawable.bg_chip_unselected)
            holder.tvTabName.setTextColor(android.graphics.Color.parseColor("#D3CFF0"))
        }

        holder.itemView.setOnClickListener {
            onTabClick(playlist)
        }

        holder.itemView.setOnLongClickListener {
            onTabLongClick(playlist)
            true
        }
    }

    override fun getItemCount(): Int = playlists.size

    fun updatePlaylists(newList: List<PlaylistMetadata>, newSelectedId: Long) {
        playlists = newList
        selectedId = newSelectedId
        notifyDataSetChanged()
    }
}
