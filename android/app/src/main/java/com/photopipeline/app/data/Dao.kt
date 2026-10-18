package com.photopipeline.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ShootDao {
    @Query("SELECT * FROM shoots ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ShootEntity>>

    @Query("SELECT * FROM shoots WHERE shootId = :id")
    fun observe(id: String): Flow<ShootEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(shoot: ShootEntity)

    @Update
    suspend fun update(shoot: ShootEntity)
}

@Dao
interface FileDao {
    @Query("SELECT * FROM files WHERE shootId = :shootId ORDER BY originalName")
    fun observeForShoot(shootId: String): Flow<List<FileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(files: List<FileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: FileEntity)

    @Query("SELECT * FROM files WHERE shootId = :shootId AND uploadState != 'UPLOADED'")
    suspend fun pendingUploads(shootId: String): List<FileEntity>

    @Query("SELECT * FROM files WHERE identityKey = :key LIMIT 1")
    suspend fun getFile(key: String): FileEntity?
}
