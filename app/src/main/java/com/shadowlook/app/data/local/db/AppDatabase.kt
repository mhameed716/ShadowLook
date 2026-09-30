package com.shadowlook.app.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.shadowlook.app.data.local.converters.Converters
import com.shadowlook.app.data.local.dao.UnknownFaceDao
import com.shadowlook.app.data.local.dao.UserFaceDao
import com.shadowlook.app.data.local.entity.UnknownFaceEntity
import com.shadowlook.app.data.local.entity.UserFaceEntity

@Database(
    entities = [UserFaceEntity::class, UnknownFaceEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userFaceDao(): UserFaceDao
    abstract fun unknownFaceDao(): UnknownFaceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Migration من الإصدار 1 إلى 2 يحافظ على البيانات - لا يحذف شيء
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                try {
                    // إضافة عمود timestamp مع قيمة افتراضية
                    database.execSQL("ALTER TABLE known_users ADD COLUMN timestamp INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {
                    // العمود موجود مسبقاً أو خطأ
                }
                try {
                    // إضافة عمود formattedDate مع قيمة افتراضية
                    database.execSQL("ALTER TABLE known_users ADD COLUMN formattedDate TEXT NOT NULL DEFAULT ''")
                } catch (e: Exception) {
                }
                
                // تحديث القيم الافتراضية للسجلات الموجودة
                try {
                    val currentTime = System.currentTimeMillis()
                    val formattedDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                    database.execSQL("UPDATE known_users SET timestamp = $currentTime WHERE timestamp = 0")
                    database.execSQL("UPDATE known_users SET formattedDate = '$formattedDate' WHERE formattedDate = ''")
                } catch (e: Exception) {
                }
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "shadowlook_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigrationOnDowngrade() // فقط عند الرجوع لإصدار أقدم
                    // .fallbackToDestructiveMigration() تم إزالته لمنع حذف البيانات
                    .build()
                INSTANCE = instance
                instance
            }
        }
        
        // دالة لإعادة تعيين الـ instance عند الحاجة
        fun closeDatabase() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
