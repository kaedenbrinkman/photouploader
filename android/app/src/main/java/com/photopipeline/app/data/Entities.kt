package com.photopipeline.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shoots")
data class ShootEntity(
    @PrimaryKey val shootId: String,
    val albumTitle: String,
    val state: String,
    val phase: String,
    val createdAt: Long,
    val imageCount: Int,
    val totalBytes: Long,
    val estimateJson: String?,
    val backendState: String?,
    val albumId: String?,
    val actualComputeJson: String?,
)

@Entity(tableName = "files")
data class FileEntity(
    @PrimaryKey val identityKey: String,
    val shootId: String,
    val sha256: String,
    val originalName: String,
    val sizeBytes: Long,
    val storageId: Long,
    val objectHandle: Long,
    val captureTimeEpochMs: Long,
    val localPath: String?,
    val uploadState: String,
    val uploadedBytes: Long,
    val uploadSpeedBps: Long,
    val retryCount: Int,
    val lastError: String?,
    val s3UploadId: String?,
    val s3Key: String?,
)
