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
import com.google.android.material.button.MaterialButton
import com.shadowlook.app.R
import com.shadowlook.app.data.local.entity.UnknownFaceEntity
import java.io.File

class UnknownFacesAdapter(
    private val onDeleteClick: (UnknownFaceEntity) -> Unit
) : ListAdapter<UnknownFaceEntity, UnknownFacesAdapter.UnknownViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UnknownViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_unknown_user, parent, false)
        return UnknownViewHolder(view)
    }

    override fun onBindViewHolder(holder: UnknownViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class UnknownViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPhoto: ImageView = itemView.findViewById(R.id.ivUnknownPhoto)
        private val tvDate: TextView = itemView.findViewById(R.id.tvUnknownDate)
        private val tvId: TextView = itemView.findViewById(R.id.tvUnknownId)
        private val btnDelete: MaterialButton = itemView.findViewById(R.id.btnDeleteUnknown)

        fun bind(entity: UnknownFaceEntity) {
            tvDate.text = entity.formattedDate
            tvId.text = "LOG_ID: ${entity.id.toString().padStart(3, '0')} // AUTO_CAPTURE"

            val file = File(entity.imagePath)
            if (file.exists()) {
                ivPhoto.load(file)
            } else {
                ivPhoto.setImageResource(R.mipmap.ic_launcher)
            }

            btnDelete.setOnClickListener {
                onDeleteClick(entity)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<UnknownFaceEntity>() {
        override fun areItemsTheSame(oldItem: UnknownFaceEntity, newItem: UnknownFaceEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: UnknownFaceEntity, newItem: UnknownFaceEntity) = oldItem == newItem
    }
}
