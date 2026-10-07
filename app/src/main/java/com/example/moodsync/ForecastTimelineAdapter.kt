package com.example.moodsync

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ForecastTimelineAdapter :
    RecyclerView.Adapter<ForecastTimelineAdapter.ForecastTimelineViewHolder>() {

    private val items = mutableListOf<MoodPredictionEngine.ForecastPoint>()

    fun submitList(newItems: List<MoodPredictionEngine.ForecastPoint>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ForecastTimelineViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_forecast_timeline, parent, false)
        return ForecastTimelineViewHolder(view)
    }

    override fun onBindViewHolder(holder: ForecastTimelineViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ForecastTimelineViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDayLabel: TextView = itemView.findViewById(R.id.tvDayLabel)
        private val tvTimeOfDay: TextView = itemView.findViewById(R.id.tvTimeOfDay)
        private val tvPredictedMood: TextView = itemView.findViewById(R.id.tvPredictedMood)
        private val tvConfidence: TextView = itemView.findViewById(R.id.tvConfidence)
        private val tvReason: TextView = itemView.findViewById(R.id.tvReason)

        fun bind(item: MoodPredictionEngine.ForecastPoint) {
            tvDayLabel.text = item.dayLabel
            tvTimeOfDay.text = item.timeOfDay
            tvPredictedMood.text = item.predictedMood.replace("_", " ").split(" ").joinToString(" ") {
                it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }
            }
            tvConfidence.text = "${item.confidenceLabel} ${(item.confidence * 100).toInt()}%"
            tvReason.text = item.reason
        }
    }
}