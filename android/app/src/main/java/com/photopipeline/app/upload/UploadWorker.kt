package com.photopipeline.app.upload

import android.app.ForegroundServiceStartNotAllowedException
import android.app.InvalidForegroundServiceTypeException
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.photopipeline.app.PhotoPipelineApp
import com.photopipeline.app.api.ApiRequestException
import com.photopipeline.app.core.PipelineLog
import com.photopipeline.app.data.FileEntity
import com.photopipeline.app.data.PrepareCameraResult
import com.photopipeline.app.mtp.UsbAccessState
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
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
        val shootId = inputData.getString("shootId") ?: return failWorker("missing shootId in work input")
        val files = repo.getPendingFiles(shootId)
        if (files.isEmpty()) return Result.success()
        val usesCameraStream = files.any { it.isCameraStreamUpload() }
        UploadMetricsStore.update {
            it.copy(
                shootId = shootId,
                filesTotal = files.size,
                network = network.snapshot(),
                transferAccelerationActive = prefs.transferAcceleration,
                lastError = null,
                pausedReason = null,
            )
        }
        promoteToForeground(usesCameraStream)

        when (val prep = repo.prepareCameraForUpload(shootId)) {
            is PrepareCameraResult.Error -> return failWorker(prep.message, usesCameraStream)
            PrepareCameraResult.Ready -> Unit
        }

        if (!network.canUpload(prefs.wifiOnly, prefs.allowCellular)) {
            val reason = pausedMessage(network.snapshot())
            return retryWorker(reason, usesCameraStream, retryable = !usesCameraStream)
        }
        if (network.snapshot().captivePortal) {
            return retryWorker(
                "Captive portal — sign in to hotel Wi-Fi",
                usesCameraStream,
                retryable = !usesCameraStream,
            )
        }

        var filesDone = repo.countUploadedFiles(shootId)
        for (file in files) {
            if (file.isCameraStreamUpload()) {
                usb.refreshProbe(log = false)
                if (usb.accessState.value != UsbAccessState.READY) {
                    val reason = "Camera not open for MTP (USB state=${usb.accessState.value})"
                    repo.markFileFailed(file.identityKey, reason)
                    return failWorker(reason, usesCameraStream)
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
                val reason = e.message ?: "presign expired"
                repo.incrementRetry(file.identityKey, reason)
                return retryWorker(reason, usesCameraStream, retryable = true)
            } catch (e: Exception) {
                val reason = uploadFailureMessage(e)
                PipelineLog.e("upload", "File ${file.originalName} failed: $reason", e)
                repo.markFileFailed(file.identityKey, reason)
                UploadMetricsStore.update {
                    it.copy(
                        retries = it.retries + 1,
                        lastError = reason,
                        network = network.snapshot(),
                    )
                }
                if (usesCameraStream || runAttemptCount >= 8) {
                    return failWorker(reason, usesCameraStream)
                }
                delay(RetryPolicy.backoffMs(runAttemptCount))
                return retryWorker(reason, usesCameraStream, retryable = true)
            }
            UploadMetricsStore.update { it.copy(filesDone = filesDone, network = network.snapshot()) }
        }
        prefs.lastMeasuredUploadBps = UploadMetricsStore.metrics.value.averageBps
        repo.uploadManifestIfReady(shootId)
        UploadMetricsStore.clear()
        return Result.success()
    }

    private suspend fun retryWorker(reason: String, usesCameraStream: Boolean, retryable: Boolean): Result {
        PipelineLog.w("upload", "RETRY attempt=$runAttemptCount reason=$reason")
        UploadMetricsStore.update {
            it.copy(
                pausedReason = reason,
                lastError = reason,
                network = network.snapshot(),
            )
        }
        promoteToForeground(usesCameraStream)
        return if (retryable && runAttemptCount < 8) {
            Result.retry()
        } else {
            failWorker(reason, usesCameraStream)
        }
    }

    private suspend fun failWorker(reason: String, usesCameraStream: Boolean = enqueueUsesCamera): Result {
        PipelineLog.e("upload", "FAIL reason=$reason")
        UploadMetricsStore.update {
            it.copy(
                pausedReason = reason,
                lastError = reason,
                network = network.snapshot(),
            )
        }
        promoteToForeground(usesCameraStream)
        return Result.failure()
    }

    private fun uploadFailureMessage(e: Exception): String = when (e) {
        is ApiRequestException -> e.message ?: "API error"
        is IOException -> e.message ?: "network I/O error"
        else -> e.message ?: e.javaClass.simpleName
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
                    if (!resp.isSuccessful) {
                        throw ApiRequestException(
                            "S3 part PUT HTTP ${resp.code}",
                            resp.code,
                        )
                    }
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
