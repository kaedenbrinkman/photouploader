package com.photopipeline.app.upload

import android.app.ForegroundServiceStartNotAllowedException
import android.app.InvalidForegroundServiceTypeException
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
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
    private val sessionSpeed = TransferSpeedTracker()

    /** Used by [getForegroundInfo] before [doWork] runs (inputData from enqueue). */
    private val enqueueUsesCamera: Boolean
        get() = inputData.getBoolean(KEY_USES_CAMERA_STREAM, true)

    override suspend fun doWork(): Result {
        val shootId = inputData.getString("shootId") ?: return failWorker("missing shootId in work input")
        val files = repo.getPendingFiles(shootId)
        if (files.isEmpty()) return Result.success()
        val usesCameraStream = files.any { it.isCameraStreamUpload() }
        sessionSpeed.reset(files.sumOf { it.sizeBytes }.coerceAtLeast(1))
        UploadMetricsStore.update {
            it.copy(
                shootId = shootId,
                filesTotal = files.size,
                network = network.snapshot(),
                transferAccelerationActive = prefs.transferAcceleration,
                lastError = null,
                pausedReason = null,
                peakBps = 0,
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
        val failedKeys = mutableListOf<String>()

        suspend fun uploadOne(file: FileEntity): Boolean {
            if (file.isCameraStreamUpload()) {
                usb.refreshProbe(log = false)
                if (usb.accessState.value != UsbAccessState.READY) {
                    val reason = "Camera not open for MTP (USB state=${usb.accessState.value})"
                    repo.markFileFailed(file.identityKey, reason)
                    return false
                }
            }
            return try {
                if (!file.localPath.isNullOrBlank() && File(file.localPath).exists()) {
                    uploadLocalFile(shootId, file)
                } else {
                    uploadStreaming(shootId, file)
                }
                repo.markFileUploaded(file.identityKey)
                if (file.isCameraStreamUpload()) {
                    usb.refreshCameraSnapshot()
                }
                filesDone++
                UploadMetricsStore.update { it.copy(filesDone = filesDone, network = network.snapshot()) }
                true
            } catch (e: StreamingCameraUploader.PresignExpiredException) {
                throw e
            } catch (e: Exception) {
                val reason = uploadFailureMessage(e)
                PipelineLog.e("upload", "File ${file.originalName} failed: $reason", e)
                repo.markFileFailed(file.identityKey, reason)
                failedKeys += file.identityKey
                UploadMetricsStore.update {
                    it.copy(
                        retries = it.retries + 1,
                        lastError = reason,
                        network = network.snapshot(),
                    )
                }
                false
            }
        }

        for (file in files) {
            try {
                uploadOne(file)
            } catch (e: StreamingCameraUploader.PresignExpiredException) {
                val reason = e.message ?: "presign expired"
                repo.incrementRetry(file.identityKey, reason)
                return retryWorker(reason, usesCameraStream, retryable = true)
            }
        }

        if (failedKeys.isNotEmpty()) {
            var retryRound = 0
            while (failedKeys.isNotEmpty() && retryRound < 3) {
                retryRound++
                delay(RetryPolicy.backoffMs(retryRound))
                PipelineLog.i("upload", "Retry pass $retryRound for ${failedKeys.size} failed file(s)")
                val retryIds = failedKeys.toList()
                for (identityKey in retryIds) {
                    repo.retryFile(identityKey)
                    val file = repo.db.fileDao().getFile(identityKey) ?: continue
                    try {
                        if (uploadOne(file)) {
                            failedKeys.remove(identityKey)
                        }
                    } catch (e: StreamingCameraUploader.PresignExpiredException) {
                        val reason = e.message ?: "presign expired"
                        return retryWorker(reason, usesCameraStream, retryable = true)
                    }
                }
            }
        }

        if (failedKeys.isNotEmpty()) {
            val n = failedKeys.size
            return failWorker("$n file(s) failed upload — use Retry on the shoot screen", usesCameraStream)
        }

        prefs.lastMeasuredUploadBps = sessionSpeed.snapshot().averageBps
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
        return Result.failure(workDataOf(KEY_ERROR to reason))
    }

    private fun uploadFailureMessage(e: Exception): String = when (e) {
        is ApiRequestException -> e.message ?: "API error"
        is IOException -> e.message ?: "network I/O error"
        else -> e.message ?: e.javaClass.simpleName
    }

    private suspend fun uploadStreaming(shootId: String, file: FileEntity) {
        repo.markFileUploading(file.identityKey)
        var lastUploaded = 0L
        val result = streamUploader.uploadFile(shootId, file) { uploaded, snap, partsDone, partsTotal, parts, uploadId, s3Key ->
            val delta = (uploaded - lastUploaded).toInt()
            if (delta > 0) sessionSpeed.recordBytes(delta)
            lastUploaded = uploaded
            val sessionSnap = sessionSpeed.snapshot()
            UploadMetricsStore.update {
                it.copy(
                    currentBps = snap.currentBps,
                    averageBps = sessionSnap.averageBps,
                    peakBps = maxOf(it.peakBps, snap.peakBps, sessionSnap.peakBps),
                    bytesSent = uploaded,
                    totalBytes = file.sizeBytes,
                    partsDone = partsDone,
                    partsTotal = partsTotal,
                    etaSeconds = sessionSnap.etaSeconds,
                    sessionBytes = sessionSnap.sessionBytes,
                    sessionElapsedMs = sessionSnap.sessionElapsedMs,
                    network = network.snapshot(),
                )
            }
            UploadMetricsStore.pushSpeedSample(snap.currentBps)
            repo.updateUploadProgress(
                file.identityKey,
                uploaded,
                snap.currentBps,
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
        PipelineLog.i("upload", "file start (local) name=${file.originalName} key=$key size=${file.sizeBytes}")
        val fileSpeed = TransferSpeedTracker()
        fileSpeed.reset(file.sizeBytes)
        val partSize = prefs.partSizeBytes
        val plans = MultipartPlanner.planParts(file.sizeBytes, partSize)
        val client = StreamingCameraUploader.defaultHttp()
        RandomAccessFile(local, "r").use { raf ->
            for (plan in plans) {
                if (parts.any { it.partNumber == plan.partNumber }) continue
                val partStartMs = System.currentTimeMillis()
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
                val putStartMs = System.currentTimeMillis()
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
                val putMs = System.currentTimeMillis() - putStartMs
                fileSpeed.recordBytes(buf.size)
                sessionSpeed.recordBytes(buf.size)
                val snap = fileSpeed.snapshot()
                val sessionSnap = sessionSpeed.snapshot()
                PipelineLog.i(
                    "upload",
                    "part key=$key part=${plan.partNumber}/${plans.size} bytes=${buf.size} putMs=$putMs " +
                        "totalMs=${System.currentTimeMillis() - partStartMs}",
                )
                repo.updateUploadProgress(
                    file.identityKey,
                    min(file.sizeBytes, plan.offset + plan.length),
                    snap.currentBps,
                    uploadId,
                    key!!,
                    parts,
                )
                UploadMetricsStore.update {
                    it.copy(
                        currentBps = snap.currentBps,
                        averageBps = sessionSnap.averageBps,
                        peakBps = maxOf(it.peakBps, snap.peakBps, sessionSnap.peakBps),
                        bytesSent = min(file.sizeBytes, plan.offset + plan.length),
                        totalBytes = file.sizeBytes,
                        partsDone = parts.size,
                        partsTotal = plans.size,
                        etaSeconds = sessionSnap.etaSeconds,
                        sessionBytes = sessionSnap.sessionBytes,
                        sessionElapsedMs = sessionSnap.sessionElapsedMs,
                        network = network.snapshot(),
                    )
                }
                UploadMetricsStore.pushSpeedSample(snap.currentBps)
            }
        }
        val completeStart = System.currentTimeMillis()
        MultipartComplete.completeWithRetry(
            repo.api,
            shootId,
            uploadRef,
            key!!,
            uploadId!!,
            parts,
            accel,
            file.sizeBytes,
        )
        PipelineLog.i(
            "upload",
            "file complete (local) name=${file.originalName} key=$key ms=${System.currentTimeMillis() - completeStart}",
        )
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
        const val KEY_ERROR = "error"
    }
}
