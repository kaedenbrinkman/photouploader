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
        return Result.success()
    }

    private suspend fun uploadFile(shootId: String, file: FileEntity) {
        val local = File(file.localPath ?: error("missing path"))
        val (uploadId, key) = repo.api.createMultipart(shootId, file.sha256, file.originalName)
        val partSize = 8L * 1024 * 1024
        var part = 1
        var offset = 0L
        val client = OkHttpClient()
        RandomAccessFile(local, "r").use { raf ->
            val t0 = System.currentTimeMillis()
            while (offset < file.sizeBytes) {
                val len = minOf(partSize, file.sizeBytes - offset).toInt()
                val buf = ByteArray(len)
                raf.seek(offset)
                raf.readFully(buf)
                val url = repo.api.presignPart(
                    shootId,
                    file.sha256,
                    part,
                    uploadId,
                    file.originalName,
                    file.sizeBytes,
                )
                val req = Request.Builder().url(url).put(buf.toRequestBody(OCTET)).build()
                client.newCall(req).execute().close()
                offset += len
                part++
                val dt = max(1, System.currentTimeMillis() - t0)
                val speed = (offset * 1000) / dt
                repo.updateUploadProgress(file.identityKey, offset, speed, uploadId, key)
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        UploadNotifications.foregroundInfo(applicationContext)

    companion object {
        private val OCTET = "application/octet-stream".toMediaType()
    }
}
