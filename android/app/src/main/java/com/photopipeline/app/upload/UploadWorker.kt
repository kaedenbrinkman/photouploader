package com.photopipeline.app.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.photopipeline.app.PhotoPipelineApp
import com.photopipeline.app.data.FileEntity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max

class UploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val repo = (appContext.applicationContext as PhotoPipelineApp).repository

    override suspend fun doWork(): Result {
        val shootId = inputData.getString("shootId") ?: return Result.failure()
        setForeground(getForegroundInfo())
        val pending = repo.getPendingFiles(shootId)
        for (file in pending) {
            try {
                uploadFile(shootId, file)
                repo.markFileUploaded(file.identityKey)
            } catch (e: Exception) {
                repo.markFileFailed(file.identityKey, e.message ?: "upload failed")
                if (runAttemptCount < 5) return Result.retry()
            }
        }
        repo.uploadManifestIfReady(shootId)
        return Result.success()
    }

    private suspend fun uploadFile(shootId: String, file: FileEntity) {
        val local = File(file.localPath ?: error("missing path"))
        var parts = UploadParts.parse(file.uploadPartsJson)
        var uploadId = file.s3UploadId
        var key = file.s3Key
        if (uploadId.isNullOrBlank() || key.isNullOrBlank()) {
            val created = repo.api.createMultipart(shootId, file.sha256, file.originalName)
            uploadId = created.first
            key = created.second
            repo.updateUploadProgress(file.identityKey, file.uploadedBytes, 0, uploadId, key, parts)
        }
        if (parts.isEmpty() && uploadId != null && key != null) {
            parts = repo.api.listParts(shootId, uploadId, key).map {
                UploadPart(it.first, it.second)
            }
            repo.updateUploadProgress(file.identityKey, file.uploadedBytes, 0, uploadId, key, parts)
        }

        val partSize = PART_SIZE_BYTES
        val totalParts = ((file.sizeBytes + partSize - 1) / partSize).toInt()
        val completedNumbers = parts.map { it.partNumber }.toSet()
        val client = OkHttpClient()
        RandomAccessFile(local, "r").use { raf ->
            val t0 = System.currentTimeMillis()
            for (partNumber in 1..totalParts) {
                if (partNumber in completedNumbers) continue
                val offset = (partNumber - 1L) * partSize
                val len = minOf(partSize, file.sizeBytes - offset).toInt()
                val buf = ByteArray(len)
                raf.seek(offset)
                raf.readFully(buf)
                val url = repo.api.presignPart(
                    shootId,
                    file.sha256,
                    partNumber,
                    uploadId!!,
                    file.originalName,
                    file.sizeBytes,
                )
                val response = client.newCall(
                    Request.Builder().url(url).put(buf.toRequestBody(OCTET)).build(),
                ).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) error("part upload failed: ${resp.code}")
                    val eTag = resp.header("ETag")?.trim()?.trim('"')
                        ?: error("missing ETag for part $partNumber")
                    parts = UploadParts.merge(parts, UploadPart(partNumber, eTag))
                    val uploadedBytes = minOf(file.sizeBytes, offset + len)
                    val dt = max(1, System.currentTimeMillis() - t0)
                    val speed = (uploadedBytes * 1000) / dt
                    repo.updateUploadProgress(
                        file.identityKey,
                        uploadedBytes,
                        speed,
                        uploadId,
                        key!!,
                        parts,
                    )
                }
            }
        }
        repo.api.completeMultipart(
            shootId,
            file.sha256,
            key!!,
            uploadId!!,
            parts,
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        UploadNotifications.foregroundInfo(applicationContext)

    companion object {
        private const val PART_SIZE_BYTES = 8L * 1024 * 1024
        private val OCTET = "application/octet-stream".toMediaType()
    }
}
