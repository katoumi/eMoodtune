package com.example.moodsync

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class StatsBarAdapter : RecyclerView.Adapter<StatsBarAdapter.StatsBarViewHolder>() {

    private val items = mutableListOf<StatsBarItem>()

    fun submitList(newItems: List<StatsBarItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StatsBarViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stats_bar, parent, false)
        return StatsBarViewHolder(view)
    }

    override fun onBindViewHolder(holder: StatsBarViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class StatsBarViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLabel: TextView = itemView.findViewById(R.id.tvLabel)
        private val tvValue: TextView = itemView.findViewById(R.id.tvValue)
        private val viewBarFill: View = itemView.findViewById(R.id.viewBarFill)

        fun bind(item: StatsBarItem) {
            tvLabel.text = item.label
            tvValue.text = item.value.toString()

            val percent = if (item.maxValue > 0) {
                item.value.toFloat() / item.maxValue.toFloat()
            } else {
                0f
            }

            viewBarFill.post {
                val parent = viewBarFill.parent as View
                val totalWidth = parent.width
                val minWidth = if (item.value > 0) 36 else 0
                val targetWidth = (totalWidth * percent).toInt().coerceAtLeast(minWidth)

                val params = viewBarFill.layoutParams
                params.width = targetWidth
                viewBarFill.layoutParams = params
                viewBarFill.alpha = if (item.value > 0) 1f else 0.35f
            }
        }
    }
}