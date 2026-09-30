package com.shadowlook.app.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "shadowlook_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
