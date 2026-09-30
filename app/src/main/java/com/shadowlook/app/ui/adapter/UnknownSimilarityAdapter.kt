package com.shadowlook.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.shadowlook.app.R
import com.shadowlook.app.ml.FaceAnalyzer
import java.io.File

class UnknownSimilarityAdapter : ListAdapter<FaceAnalyzer.UnknownSimilarity, UnknownSimilarityAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_unknown_similarity, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPhoto: ImageView = itemView.findViewById(R.id.ivUnknownSimilar)
        private val tvSimilarity: TextView = itemView.findViewById(R.id.tvSimilarityPercent)
        private val tvDate: TextView = itemView.findViewById(R.id.tvUnknownDateSimilar)

        fun bind(item: FaceAnalyzer.UnknownSimilarity) {
            try {
                val file = File(item.entity.imagePath)
                if (file.exists()) {
                    ivPhoto.load(file)
                } else {
                    ivPhoto.setImageResource(R.mipmap.ic_launcher)
                }

                tvSimilarity.text = "${String.format("%.1f", item.similarity)}%"
                // لون حسب نسبة التشابه
                tvSimilarity.setTextColor(
                    when {
                        item.similarity >= 85 -> android.graphics.Color.parseColor("#FF0055")
                        item.similarity >= 70 -> android.graphics.Color.parseColor("#FF8A65")
                        else -> android.graphics.Color.parseColor("#FFAB91")
                    }
                )

                tvDate.text = item.entity.formattedDate.substring(0, 10)
            } catch (e: Throwable) {
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<FaceAnalyzer.UnknownSimilarity>() {
        override fun areItemsTheSame(oldItem: FaceAnalyzer.UnknownSimilarity, newItem: FaceAnalyzer.UnknownSimilarity) = oldItem.entity.id == newItem.entity.id
        override fun areContentsTheSame(oldItem: FaceAnalyzer.UnknownSimilarity, newItem: FaceAnalyzer.UnknownSimilarity) = oldItem.entity.id == newItem.entity.id && oldItem.similarity == newItem.similarity
    }
}
