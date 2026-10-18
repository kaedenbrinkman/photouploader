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
    val maxRuntimeMinutes: Int? = null,
    val deadlineAt: Long? = null,
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
    /** CAMERA_STREAM (default) streams MTP→S3; LOCAL uses localPath copy mode. */
    val uploadSource: String = "CAMERA_STREAM",
    val uploadState: String,
    val uploadedBytes: Long,
    val uploadSpeedBps: Long,
    val retryCount: Int,
    val lastError: String?,
    val s3UploadId: String?,
    val s3Key: String?,
    /** JSON array of {partNumber,eTag} for S3 multipart resume */
    val uploadPartsJson: String = "[]",
    val manifestUploaded: Boolean = false,
)
