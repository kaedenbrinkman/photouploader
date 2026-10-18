package com.photopipeline.app.upload

import android.app.ForegroundServiceStartNotAllowedException
import android.app.InvalidForegroundServiceTypeException
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.photopipeline.app.PhotoPipelineApp
import com.photopipeline.app.core.PipelineLog
import com.photopipeline.app.data.FileEntity
import com.photopipeline.app.mtp.UsbAccessState
import kotlinx.coroutines.delay
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class UploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val app = appContext.applicationContext as PhotoPipelineApp
    private val repo = app.repository
    private val usb = app.usbCameraManager
    private val prefs = app.uploadPreferences
    private val network = NetworkStatusMonitor(appContext)
    private val streamUploader = StreamingCameraUploader(usb, repo.api, StreamingCameraUploader.defaultHttp(), prefs)

    /** Used by [getForegroundInfo] before [doWork] runs (inputData from enqueue). */
    private val enqueueUsesCamera: Boolean
        get() = inputData.getBoolean(KEY_USES_CAMERA_STREAM, true)

    override suspend fun doWork(): Result {
        val shootId = inputData.getString("shootId") ?: return Result.failure()
        val files = repo.getPendingFiles(shootId)
        if (files.isEmpty()) return Result.success()
        val usesCameraStream = files.any { it.isCameraStreamUpload() }
        UploadMetricsStore.update {
            it.copy(
                shootId = shootId,
                filesTotal = files.size,
                network = network.snapshot(),
                transferAccelerationActive = prefs.transferAcceleration,
            )
        }
        if (!network.canUpload(prefs.wifiOnly, prefs.allowCellular)) {
            UploadMetricsStore.update {
                it.copy(
                    pausedReason = pausedMessage(network.snapshot()),
                    network = network.snapshot(),
                )
            }
            promoteToForeground(usesCameraStream)
            return Result.retry()
        }
        if (network.snapshot().captivePortal) {
            UploadMetricsStore.update {
                it.copy(pausedReason = "Captive portal — sign in to hotel Wi-Fi", network = network.snapshot())
            }
            promoteToForeground(usesCameraStream)
            return Result.retry()
        }
        promoteToForeground(usesCameraStream)
        var filesDone = repo.countUploadedFiles(shootId)
        for (file in files) {
            if (file.isCameraStreamUpload()) {
                if (usb.accessState.value != UsbAccessState.READY) {
                    repo.markFilePaused(file.identityKey, "Reconnect camera to continue upload")
                    UploadMetricsStore.update {
                        it.copy(pausedReason = "Reconnect camera to continue upload", network = network.snapshot())
                    }
                    return Result.retry()
                }
            }
            try {
                if (!file.localPath.isNullOrBlank() && File(file.localPath).exists()) {
                    uploadLocalFile(shootId, file)
                } else {
                    uploadStreaming(shootId, file)
                }
                repo.markFileUploaded(file.identityKey)
                filesDone++
            } catch (e: StreamingCameraUploader.PresignExpiredException) {
                repo.incrementRetry(file.identityKey, e.message ?: "presign expired")
                return Result.retry()
            } catch (e: Exception) {
                repo.markFileFailed(file.identityKey, e.message ?: "upload failed")
                UploadMetricsStore.update {
                    it.copy(
                        retries = it.retries + 1,
                        lastError = e.message,
                        network = network.snapshot(),
                    )
                }
                if (runAttemptCount < 8) {
                    delay(RetryPolicy.backoffMs(runAttemptCount))
                    return Result.retry()
                }
            }
            UploadMetricsStore.update { it.copy(filesDone = filesDone, network = network.snapshot()) }
        }
        prefs.lastMeasuredUploadBps = UploadMetricsStore.metrics.value.averageBps
        repo.uploadManifestIfReady(shootId)
        UploadMetricsStore.clear()
        return Result.success()
    }

    private suspend fun uploadStreaming(shootId: String, file: FileEntity) {
        repo.markFileUploading(file.identityKey)
        val result = streamUploader.uploadFile(shootId, file) { uploaded, speed, partsDone, partsTotal, parts, uploadId, s3Key ->
            UploadMetricsStore.update {
                it.copy(
                    currentBps = speed,
                    averageBps = speed,
                    bytesSent = uploaded,
                    totalBytes = file.sizeBytes,
                    partsDone = partsDone,
                    partsTotal = partsTotal,
                    network = network.snapshot(),
                )
            }
            repo.updateUploadProgress(
                file.identityKey,
                uploaded,
                speed,
                uploadId,
                s3Key,
                parts,
            )
        }
        repo.updateFileContentSha256(file.identityKey, result.contentSha256, result.parts)
    }

    private suspend fun uploadLocalFile(shootId: String, file: FileEntity) {
        val local = File(file.localPath!!)
        var parts = UploadParts.parse(file.uploadPartsJson)
        var uploadId = file.s3UploadId
        var key = file.s3Key
        val uploadRef = file.sha256.ifBlank {
            com.photopipeline.app.core.metadataUploadSha256(
                file.storageId,
                file.objectHandle,
                file.sizeBytes,
                file.captureTimeEpochMs,
            )
        }
        val accel = prefs.transferAcceleration
        if (uploadId.isNullOrBlank() || key.isNullOrBlank()) {
            val created = repo.api.createMultipart(shootId, uploadRef, file.originalName, accel)
            uploadId = created.first
            key = created.second
        }
        val partSize = prefs.partSizeBytes
        val plans = MultipartPlanner.planParts(file.sizeBytes, partSize)
        val client = StreamingCameraUploader.defaultHttp()
        RandomAccessFile(local, "r").use { raf ->
            for (plan in plans) {
                if (parts.any { it.partNumber == plan.partNumber }) continue
                val buf = ByteArray(plan.length.toInt())
                raf.seek(plan.offset)
                raf.readFully(buf)
                val presigned = repo.api.presignPart(
                    shootId,
                    uploadRef,
                    plan.partNumber,
                    uploadId!!,
                    file.originalName,
                    file.sizeBytes,
                    accel,
                )
                UploadMetricsStore.update {
                    it.copy(transferAccelerationActive = presigned.transferAcceleration || accel)
                }
                client.newCall(
                    Request.Builder().url(presigned.url).put(buf.toRequestBody(OCTET)).build(),
                ).execute().use { resp ->
                    if (!resp.isSuccessful) error("part upload failed")
                    val eTag = resp.header("ETag")?.trim()?.trim('"') ?: error("missing ETag")
                    parts = UploadParts.merge(parts, UploadPart(plan.partNumber, eTag))
                }
                val speed = 0L
                repo.updateUploadProgress(
                    file.identityKey,
                    min(file.sizeBytes, plan.offset + plan.length),
                    speed,
                    uploadId,
                    key!!,
                    parts,
                )
            }
        }
        repo.api.completeMultipart(shootId, uploadRef, key!!, uploadId!!, parts, accel)
    }

    private fun pausedMessage(net: NetworkSnapshot): String = when {
        !net.online -> "Offline — upload paused until network returns"
        net.captivePortal -> "Captive portal detected"
        prefs.wifiOnly && net.type == "cellular" -> "Wi-Fi only — waiting for Wi-Fi"
        else -> "Upload paused"
    }

    private suspend fun promoteToForeground(useConnectedDevice: Boolean) {
        try {
            setForeground(buildForeground(useConnectedDevice))
        } catch (e: ForegroundServiceStartNotAllowedException) {
            logForegroundDegraded(e)
        } catch (e: InvalidForegroundServiceTypeException) {
            logForegroundDegraded(e)
        }
    }

    private fun logForegroundDegraded(e: Exception) {
        PipelineLog.w(
            "upload",
            "Foreground service unavailable (upload continues in background): ${e.message}",
            e,
        )
    }

    private fun buildForeground(useConnectedDevice: Boolean = enqueueUsesCamera): ForegroundInfo {
        val m = UploadMetricsStore.metrics.value
        return UploadNotifications.foregroundInfo(
            applicationContext,
            m.currentBps,
            m.etaSeconds,
            m.filesDone,
            m.filesTotal,
            useConnectedDevice = useConnectedDevice,
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = buildForeground(enqueueUsesCamera)

    private fun FileEntity.isCameraStreamUpload(): Boolean =
        uploadSource == "CAMERA_STREAM" || localPath.isNullOrBlank()

    companion object {
        private val OCTET = "application/octet-stream".toMediaType()
        const val KEY_USES_CAMERA_STREAM = "usesCameraStream"
    }
}
