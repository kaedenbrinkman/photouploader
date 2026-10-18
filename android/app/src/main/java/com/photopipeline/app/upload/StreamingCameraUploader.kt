package com.photopipeline.app.upload

import android.mtp.MtpDevice
import com.photopipeline.app.api.ApiClient
import com.photopipeline.app.core.PipelineLog
import com.photopipeline.app.core.metadataUploadSha256
import com.photopipeline.app.data.FileEntity
import com.photopipeline.app.mtp.UsbCameraManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

class StreamingCameraUploader(
    private val usb: UsbCameraManager,
    private val api: ApiClient,
    private val http: OkHttpClient = defaultHttp(),
    private val prefs: UploadPreferences,
) {
    data class Result(val contentSha256: String, val parts: List<UploadPart>)

    suspend fun uploadFile(
        shootId: String,
        file: FileEntity,
        onProgress: suspend (
            uploadedBytes: Long,
            speed: TransferSpeedTracker.Snapshot,
            partsDone: Int,
            partsTotal: Int,
            parts: List<UploadPart>,
            uploadId: String,
            s3Key: String,
        ) -> Unit,
    ): Result {
        val fileStartMs = System.currentTimeMillis()
        val mtp = usb.openMtpDevice() ?: throw IOException("Camera not connected — reconnect to continue upload")
        val handle = file.objectHandle.toInt()
        val uploadRef = metadataUploadSha256(
            file.storageId,
            file.objectHandle,
            file.sizeBytes,
            file.captureTimeEpochMs,
        )
        val partSize = prefs.partSizeBytes
        val plans = MultipartPlanner.planParts(file.sizeBytes, partSize)
        var parts = UploadParts.parse(file.uploadPartsJson)
        var uploadId = file.s3UploadId
        var key = file.s3Key
        if (uploadId.isNullOrBlank() || key.isNullOrBlank()) {
            val accel = prefs.transferAcceleration
            val created = api.createMultipart(shootId, uploadRef, file.originalName, accel)
            UploadMetricsStore.update { it.copy(transferAccelerationActive = accel) }
            uploadId = created.first
            key = created.second
        }
        PipelineLog.i(
            "upload",
            "file start name=${file.originalName} key=$key size=${file.sizeBytes} parts=${plans.size}",
        )
        if (parts.isEmpty()) {
            runCatching {
                parts = api.listParts(shootId, uploadId!!, key!!, prefs.transferAcceleration)
                    .map { UploadPart(it.first, it.second) }
            }
        }
        onProgress(
            0,
            TransferSpeedTracker.Snapshot(0, 0, 0, 0, file.sizeBytes, null),
            parts.size,
            plans.size,
            parts,
            uploadId!!,
            key!!,
        )
        val completedNumbers = parts.map { it.partNumber }.toSet()
        val hasher = IncrementalHasher()
        val speed = TransferSpeedTracker()
        speed.reset(file.sizeBytes)
        val buffer = ByteArray(min(partSize, 16L * 1024 * 1024).toInt())
        val mtpRef = MtpDeviceRef(mtp)
        val recoverMtp: () -> MtpDevice? = {
            usb.closeMtpSession()
            usb.openMtpDevice()
        }
        val firstIncomplete = plans.firstOrNull { it.partNumber !in completedNumbers }
        if (firstIncomplete != null && firstIncomplete.offset > 0) {
            hashCameraRange(mtpRef, handle, hasher, 0, firstIncomplete.offset, buffer, recoverMtp)
        }
        for (plan in plans) {
            if (plan.partNumber in completedNumbers) continue
            var attempt = 0
            while (true) {
                try {
                    val partStartMs = System.currentTimeMillis()
                    val read = MtpPartReader.readPart(
                        mtpRef,
                        handle,
                        plan.offset,
                        plan.length.toInt(),
                        buffer,
                        recoverMtp,
                    )
                    if (read <= 0) throw IOException("MTP read 0 bytes at ${plan.offset}")
                    hasher.update(buffer, 0, read)
                    val presigned = api.presignPart(
                        shootId,
                        uploadRef,
                        plan.partNumber,
                        uploadId!!,
                        file.originalName,
                        file.sizeBytes,
                        prefs.transferAcceleration,
                    )
                    UploadMetricsStore.update {
                        it.copy(
                            transferAccelerationActive = presigned.transferAcceleration ||
                                presigned.url.contains("s3-accelerate", ignoreCase = true),
                        )
                    }
                    val url = presigned.url
                    val body = buffer.copyOf(read).toRequestBody(OCTET)
                    val putStartMs = System.currentTimeMillis()
                    val response = http.newCall(Request.Builder().url(url).put(body).build()).execute()
                    response.use { resp ->
                        if (resp.code == 403 || resp.code == 400) {
                            throw PresignExpiredException("presign expired")
                        }
                        if (!resp.isSuccessful) throw IOException("part ${plan.partNumber} HTTP ${resp.code}")
                        val eTag = resp.header("ETag")?.trim()?.trim('"')
                            ?: throw IOException("missing ETag part ${plan.partNumber}")
                        parts = UploadParts.merge(parts, UploadPart(plan.partNumber, eTag))
                    }
                    val putMs = System.currentTimeMillis() - putStartMs
                    val partMs = System.currentTimeMillis() - partStartMs
                    PipelineLog.i(
                        "upload",
                        "part key=$key part=${plan.partNumber}/${plans.size} bytes=$read putMs=$putMs totalMs=$partMs",
                    )
                    val uploadedBytes = min(file.sizeBytes, plan.offset + read)
                    speed.recordBytes(read)
                    val snap = speed.snapshot()
                    onProgress(
                        uploadedBytes,
                        snap,
                        parts.size,
                        plans.size,
                        parts,
                        uploadId!!,
                        key!!,
                    )
                    UploadMetricsStore.pushSpeedSample(snap.currentBps)
                    break
                } catch (e: PresignExpiredException) {
                    throw e
                } catch (e: Exception) {
                    attempt++
                    if (attempt >= 5) throw e
                    Thread.sleep(RetryPolicy.backoffMs(attempt))
                }
            }
        }
        val completeStart = System.currentTimeMillis()
        api.completeMultipart(shootId, uploadRef, key!!, uploadId!!, parts, prefs.transferAcceleration)
        PipelineLog.i(
            "upload",
            "file complete name=${file.originalName} key=$key ms=${System.currentTimeMillis() - completeStart} " +
                "sessionMs=${System.currentTimeMillis() - fileStartMs}",
        )
        return Result(hasher.digestHex(), parts)
    }

    private fun hashCameraRange(
        mtpRef: MtpDeviceRef,
        handle: Int,
        hasher: IncrementalHasher,
        start: Long,
        endExclusive: Long,
        buffer: ByteArray,
        recoverMtp: () -> MtpDevice?,
    ) {
        var offset = start
        while (offset < endExclusive) {
            val len = min(buffer.size.toLong(), endExclusive - offset).toInt()
            val read = MtpPartReader.readPart(mtpRef, handle, offset, len, buffer, recoverMtp)
            hasher.update(buffer, 0, read)
            offset += read
        }
    }

    class PresignExpiredException(msg: String) : IOException(msg)

    companion object {
        private val OCTET = "application/octet-stream".toMediaType()

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .build()
    }
}
