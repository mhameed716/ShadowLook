package com.shadowlook.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.*

@Entity(tableName = "unknown_faces")
data class UnknownFaceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val formattedDate: String = getCurrentFormattedDate(),
    val imagePath: String,       // مسار صورة الوجه المجهول المقصوص
    val vectorEmbedding: String  // 128-float embedding
) {
    companion object {
        fun getCurrentFormattedDate(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            return sdf.format(Date())
        }
    }
}
