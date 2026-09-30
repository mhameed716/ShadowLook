package com.shadowlook.app.data.local.dao

import androidx.room.*
import com.shadowlook.app.data.local.entity.UnknownFaceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UnknownFaceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUnknown(unknown: UnknownFaceEntity): Long

    @Query("SELECT * FROM unknown_faces ORDER BY timestamp DESC")
    fun getAllUnknowns(): Flow<List<UnknownFaceEntity>>

    @Query("SELECT * FROM unknown_faces ORDER BY timestamp DESC")
    suspend fun getAllUnknownsList(): List<UnknownFaceEntity>

    @Query("SELECT * FROM unknown_faces WHERE id = :id")
    suspend fun getUnknownById(id: Int): UnknownFaceEntity?

    @Delete
    suspend fun deleteUnknown(unknown: UnknownFaceEntity)

    @Query("DELETE FROM unknown_faces WHERE id = :id")
    suspend fun deleteUnknownById(id: Int)

    @Query("DELETE FROM unknown_faces")
    suspend fun deleteAllUnknowns()

    @Query("SELECT COUNT(*) FROM unknown_faces")
    suspend fun getUnknownCount(): Int
}
