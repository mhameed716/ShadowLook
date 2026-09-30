package com.shadowlook.app.data.local.dao

import androidx.room.*
import com.shadowlook.app.data.local.entity.UserFaceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserFaceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKnown(user: UserFaceEntity): Long

    @Query("SELECT * FROM known_users ORDER BY id DESC")
    fun getAllKnowns(): Flow<List<UserFaceEntity>>

    @Query("SELECT * FROM known_users ORDER BY id DESC")
    suspend fun getAllKnownsList(): List<UserFaceEntity>

    @Query("SELECT * FROM known_users WHERE name LIKE '%' || :query || '%' OR phone LIKE '%' || :query || '%' ORDER BY id DESC")
    fun searchKnowns(query: String): Flow<List<UserFaceEntity>>

    @Query("SELECT * FROM known_users WHERE id = :id")
    suspend fun getKnownById(id: Int): UserFaceEntity?

    @Delete
    suspend fun deleteKnown(user: UserFaceEntity)

    @Query("DELETE FROM known_users WHERE id = :id")
    suspend fun deleteKnownById(id: Int)

    @Query("DELETE FROM known_users")
    suspend fun deleteAllKnowns()

    @Query("SELECT COUNT(*) FROM known_users")
    suspend fun getKnownCount(): Int
}
