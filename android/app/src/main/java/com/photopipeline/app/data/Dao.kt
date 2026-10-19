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

    @Query("DELETE FROM shoots WHERE shootId = :id")
    suspend fun delete(id: String)
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

    @Query("DELETE FROM files WHERE identityKey = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM files WHERE shootId = :shootId")
    suspend fun deleteForShoot(shootId: String)

    @Query(
        """
        SELECT COUNT(*) FROM files
        WHERE uploadState = 'UPLOADED'
          AND shootId != :shootId
          AND originalName = :name
          AND sizeBytes = :sizeBytes
          AND captureTimeEpochMs = :captureTimeEpochMs
        """,
    )
    suspend fun countUploadedElsewhereByMetadata(
        shootId: String,
        name: String,
        sizeBytes: Long,
        captureTimeEpochMs: Long,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM files
        WHERE uploadState = 'UPLOADED'
          AND shootId != :shootId
          AND sha256 != ''
          AND sha256 = :sha256
        """,
    )
    suspend fun countUploadedElsewhereBySha256(
        shootId: String,
        sha256: String,
    ): Int
}
