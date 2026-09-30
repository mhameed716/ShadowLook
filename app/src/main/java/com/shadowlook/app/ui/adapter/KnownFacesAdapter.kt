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
import com.shadowlook.app.data.local.entity.UserFaceEntity
import java.io.File

class KnownFacesAdapter(
    private val onDeleteClick: (UserFaceEntity) -> Unit
) : ListAdapter<UserFaceEntity, KnownFacesAdapter.KnownViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): KnownViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_known_user, parent, false)
        return KnownViewHolder(view)
    }

    override fun onBindViewHolder(holder: KnownViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class KnownViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPhoto: ImageView = itemView.findViewById(R.id.ivKnownPhoto)
        private val tvName: TextView = itemView.findViewById(R.id.tvKnownName)
        private val tvJob: TextView = itemView.findViewById(R.id.tvKnownJob)
        private val tvPhone: TextView = itemView.findViewById(R.id.tvKnownPhone)
        private val tvAddress: TextView = itemView.findViewById(R.id.tvKnownAddress)
        private val tvId: TextView = itemView.findViewById(R.id.tvKnownId)
        private val btnDelete: MaterialButton = itemView.findViewById(R.id.btnDeleteKnown)

        fun bind(entity: UserFaceEntity) {
            tvName.text = entity.name
            tvJob.text = entity.jobTitle.ifEmpty { "غير محدد" }
            tvPhone.text = entity.phone.ifEmpty { "لا يوجد هاتف" }
            tvAddress.text = entity.address.ifEmpty { "لا يوجد عنوان" }
            tvId.text = "ID: ${entity.id.toString().padStart(3, '0')} // SHADOW_ID"

            // تحميل الصورة
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

    class DiffCallback : DiffUtil.ItemCallback<UserFaceEntity>() {
        override fun areItemsTheSame(oldItem: UserFaceEntity, newItem: UserFaceEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: UserFaceEntity, newItem: UserFaceEntity) = oldItem == newItem
    }
}
