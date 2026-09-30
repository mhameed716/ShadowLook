package com.shadowlook.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.*

@Entity(tableName = "known_users")
data class UserFaceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,           // الاسم الكامل
    val phone: String,          // رقم الهاتف
    val jobTitle: String,       // المسمى الوظيفي
    val address: String,        // عنوان السكن
    val imagePath: String,      // مسار صورة الملف الشخصي
    val vectorEmbedding: String, // مصفوفة 128 قيمة float محفوظة كـ JSON String
    val timestamp: Long = System.currentTimeMillis(), // تاريخ التسجيل التلقائي
    val formattedDate: String = getCurrentFormattedDate() // تاريخ منسق
) {
    companion object {
        fun getCurrentFormattedDate(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            return sdf.format(Date())
        }
    }
}
