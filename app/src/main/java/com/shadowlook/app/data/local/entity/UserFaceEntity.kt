package com.shadowlook.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "known_users")
data class UserFaceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,           // الاسم الكامل
    val phone: String,          // رقم الهاتف
    val jobTitle: String,       // المسمى الوظيفي
    val address: String,        // عنوان السكن
    val imagePath: String,      // مسار صورة الملف الشخصي
    val vectorEmbedding: String // مصفوفة 128 قيمة float محفوظة كـ JSON String
)
