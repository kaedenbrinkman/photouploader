package com.photopipeline.app.data

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.photopipeline.app.api.ApiClient
import com.photopipeline.app.api.NotSignedInException
import com.photopipeline.app.core.PipelineLog
import com.photopipeline.app.core.fileIdentityKey
import com.photopipeline.app.pricing.LocalPricingEstimator
import com.photopipeline.app.mtp.MtpRepository
import com.photopipeline.app.mtp.MtpObjectInfo
import com.photopipeline.app.mtp.UsbAccessState
import com.photopipeline.app.mtp.UsbCameraManager
import com.photopipeline.app.mtp.UsbDebugLog
import com.photopipeline.app.upload.UploadPart
import com.photopipeline.app.upload.UploadParts
import com.photopipeline.app.upload.UploadWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Repository(
    val db: AppDatabase,
    context: Context,
    mtpOverride: MtpRepository? = null,
    private val usb: UsbCameraManager? = null,
    private val localPricing: LocalPricingEstimator,
) {
    private val appContext = context.applicationContext
    val mtp: MtpRepository = mtpOverride ?: error("MtpRepository required")
    private val prefs = appContext.getSharedPreferences("photopipeline", Context.MODE_PRIVATE)

    val api: ApiClient = ApiClient(
        baseUrl = prefs.getString("api_base", "https://example.execute-api.us-west-2.amazonaws.com")!!,
        idTokenProvider = { prefs.getString("id_token", null) },
    )

    fun observeShoots(): Flow<List<ShootEntity>> = db.shootDao().observeAll()
    fun observeShoot(id: String): Flow<ShootEntity?> = db.shootDao().observe(id)
    fun observeFiles(shootId: String): Flow<List<FileEntity>> = db.fileDao().observeForShoot(shootId)

    suspend fun getShoot(id: String): ShootEntity? = db.shootDao().observe(id).first()

    suspend fun getPendingFiles(shootId: String): List<FileEntity> = db.fileDao().pendingUploads(shootId)

    suspend fun markFileUploaded(identityKey: String) = patchFile(identityKey) {
        it.copy(uploadState = "UPLOADED", lastError = null)
    }

    suspend fun markFileFailed(identityKey: String, error: String) = patchFile(identityKey) {
        it.copy(uploadState = "FAILED", lastError = error, retryCount = it.retryCount + 1)
    }

    suspend fun updateUploadProgress(
        identityKey: String,
        uploadedBytes: Long,
        speedBps: Long,
        uploadId: String,
        s3Key: String,
        parts: List<UploadPart> = emptyList(),
    ) = patchFile(identityKey) {
        it.copy(
            uploadedBytes = uploadedBytes,
            uploadSpeedBps = speedBps,
            uploadState = "UPLOADING",
            s3UploadId = uploadId,
            s3Key = s3Key,
            uploadPartsJson = UploadParts.toJson(parts),
        )
    }

    private suspend fun patchFile(identityKey: String, transform: (FileEntity) -> FileEntity) {
        val cur = db.fileDao().getFile(identityKey) ?: return
        db.fileDao().upsert(transform(cur))
    }

    fun isGoogleAccountLinked(): Boolean = !prefs.getString("id_token", null).isNullOrBlank()

    fun googleUserId(): String? = prefs.getString("user_id", null)

    fun apiBaseUrl(): String = prefs.getString("api_base", "https://example.execute-api.us-west-2.amazonaws.com")!!

    fun saveSettings(apiBase: String, userId: String?, idToken: String?) {
        prefs.edit()
            .putString("api_base", apiBase.trim())
            .putString("user_id", userId?.trim()?.ifBlank { null })
            .putString("id_token", idToken?.trim()?.ifBlank { null })
            .apply()
    }

    fun isBackendConfigured(): Boolean {
        val base = apiBaseUrl()
        return base.startsWith("http") && !base.contains("example.execute-api")
    }

    fun canUseBackendApi(): Boolean = isBackendConfigured() && isGoogleAccountLinked()

    fun backendGateMessage(): String = when {
        !isBackendConfigured() -> "Backend not configured — set your API base URL in Settings."
        !isGoogleAccountLinked() -> "Link Google account in Settings before upload or submit."
        else -> ""
    }

    private fun requireBackendReady() {
        if (!canUseBackendApi()) throw NotSignedInException()
    }

    private suspend fun ensureUsbReady(): CameraImportResult.Error? {
        val manager = usb ?: return null
        manager.refreshProbe(log = true)
        when (manager.accessState.value) {
            UsbAccessState.NO_DEVICE ->
                return CameraImportResult.Error(
                    stage = "detect",
                    userMessage = "No Canon camera found. Plug in the camera via USB and tap Refresh.",
                )
            UsbAccessState.PERMISSION_REQUIRED -> {
                UsbDebugLog.log("Requesting USB permission (user dialog should appear)")
                val granted = manager.requestPermissionSuspend()
                if (!granted) {
                    return CameraImportResult.Error(
                        stage = "permission",
                        userMessage = "USB permission denied. Tap Connect again and allow access to the camera.",
                    )
                }
                manager.refreshProbe(log = true)
                if (manager.accessState.value == UsbAccessState.OPEN_FAILED) {
                    return CameraImportResult.Error(
                        stage = "open",
                        userMessage = "Permission granted but MTP interface is in use. Disconnect USB, reopen Photo Pipeline, and choose this app for USB.",
                    )
                }
            }
            UsbAccessState.PERMISSION_DENIED ->
                return CameraImportResult.Error(
                    stage = "permission",
                    userMessage = "USB permission denied. In system Settings, clear USB defaults or reconnect the cable.",
                )
            UsbAccessState.OPEN_FAILED ->
                return CameraImportResult.Error(
                    stage = "open",
                    userMessage = "Could not open the camera USB interface (another app may be using MTP). Disconnect and choose Photo Pipeline when prompted.",
                )
            UsbAccessState.READY -> Unit
        }
        if (manager.accessState.value == UsbAccessState.OPEN_FAILED) {
            return CameraImportResult.Error(
                stage = "mtp",
                userMessage = "Camera found but MTP session failed. See debug log for interface details.",
            )
        }
        mtp.refreshConnection()
        return null
    }

    suspend fun listCameraMedia(): CameraImportResult {
        PipelineLog.d("import", "listCameraMedia() begin")
        ensureUsbReady()?.let { return it }
        val objects = runCatching { mtp.listCr3Objects() }
            .getOrElse { e ->
                UsbDebugLog.log("listCr3Objects exception: ${e.message}")
                return CameraImportResult.Error(
                    stage = "list",
                    userMessage = "Failed to read camera file list: ${e.message ?: "unknown error"}",
                )
            }
        if (objects.isEmpty()) {
            return CameraImportResult.Error(
                stage = "list",
                userMessage = "Camera connected but no CR3/JPEG/RAW files found in MTP storage.",
            )
        }
        return CameraImportResult.Listed(
            objects = objects,
            statusMessage = "Found ${objects.size} file(s) on camera.",
        )
    }

    suspend fun importFromCamera(
        shootId: String,
        destDir: File,
        only: List<MtpObjectInfo>? = null,
    ): CameraImportResult {
        destDir.mkdirs()
        ensureUsbReady()?.let { return it }
        val objects = only ?: runCatching { mtp.listCr3Objects() }.getOrElse { e ->
            return CameraImportResult.Error(
                stage = "list",
                userMessage = "Failed to read camera: ${e.message ?: "unknown error"}",
            )
        }
        if (objects.isEmpty()) {
            return CameraImportResult.Error(
                stage = "import",
                userMessage = "Nothing selected to import.",
            )
        }
        val existingKeys = db.fileDao().observeForShoot(shootId).first().map { it.identityKey }.toSet()
        val entities = mutableListOf<FileEntity>()
        for (obj in objects) {
            val path = File(destDir, obj.filename).absolutePath
            val partialSha = if (File(path).length() == obj.sizeBytes && File(path).exists()) {
                sha256File(File(path))
            } else {
                mtp.importObject(obj, path) { }
            }
            val key = fileIdentityKey(
                obj.storageId,
                obj.handle,
                obj.sizeBytes,
                obj.captureTimeEpochMs,
                partialSha,
            )
            if (key in existingKeys) continue
            entities += FileEntity(
                identityKey = key,
                shootId = shootId,
                sha256 = partialSha,
                originalName = obj.filename,
                sizeBytes = obj.sizeBytes,
                storageId = obj.storageId,
                objectHandle = obj.handle,
                captureTimeEpochMs = obj.captureTimeEpochMs,
                localPath = path,
                uploadState = "PENDING",
                uploadedBytes = 0,
                uploadSpeedBps = 0,
                retryCount = 0,
                lastError = null,
                s3UploadId = null,
                s3Key = null,
            )
        }
        if (entities.isNotEmpty()) db.fileDao().upsertAll(entities)
        val allFiles = db.fileDao().observeForShoot(shootId).first()
        val totalBytes = allFiles.sumOf { it.sizeBytes }
        val existing = getShoot(shootId)
        db.shootDao().upsert(
            (existing ?: ShootEntity(
                shootId, shootId, "IMPORTING", "IMPORT", System.currentTimeMillis(), 0, 0, null, null, null, null,
            )).copy(
                imageCount = allFiles.size,
                totalBytes = totalBytes,
                phase = "IMPORT",
                state = "IMPORTING",
            ),
        )
        val msg = when {
            entities.isEmpty() -> "No new files to import (${objects.size} already in this shoot)."
            else -> "Imported ${entities.size} new file(s)."
        }
        UsbDebugLog.log(msg)
        return CameraImportResult.Imported(
            newCount = entities.size,
            totalOnShoot = allFiles.size,
            statusMessage = msg,
        )
    }

    suspend fun fetchAndStoreEstimate(shootId: String): StoredEstimate {
        val files = db.fileDao().observeForShoot(shootId).first()
        val imageCount = files.size.coerceAtLeast(1)
        val totalBytes = files.sumOf { it.sizeBytes }.coerceAtLeast(1)
        val stored = if (canUseBackendApi()) {
            val remote = runCatching { api.fetchEstimate(imageCount, totalBytes) }
            if (remote.isSuccess) {
                StoredEstimate(remote.getOrThrow(), EstimateSource.BACKEND)
            } else {
                val err = remote.exceptionOrNull()
                PipelineLog.w(
                    "estimate",
                    "Backend estimate failed (${err?.message}); using local pricing.json",
                    err,
                )
                StoredEstimate(localPricing.estimateShoot(imageCount, totalBytes), EstimateSource.LOCAL)
            }
        } else {
            PipelineLog.d(
                "estimate",
                "Using local pricing (signedIn=${isGoogleAccountLinked()} configured=${isBackendConfigured()})",
            )
            StoredEstimate(localPricing.estimateShoot(imageCount, totalBytes), EstimateSource.LOCAL)
        }
        val shoot = getShoot(shootId)
        if (shoot != null) {
            val notes = stored.response.notes.joinToString(" ")
            db.shootDao().upsert(
                shoot.copy(
                    estimateJson = JSONObject(
                        mapOf(
                            "processingMinutes" to stored.response.estimate.processingMinutes,
                            "totalShootUsd" to stored.response.estimate.totalShootUsd,
                            "computeUsd" to stored.response.estimate.computeUsd,
                            "s3StandardMonthUsd" to stored.response.estimate.s3StandardMonthUsd,
                            "source" to stored.source.name,
                            "notes" to notes,
                        ),
                    ).toString(),
                    phase = "ESTIMATE",
                ),
            )
        }
        return stored
    }

    suspend fun uploadManifestIfReady(shootId: String) {
        val files = db.fileDao().observeForShoot(shootId).first()
        if (files.isEmpty() || files.any { it.uploadState != "UPLOADED" }) return
        val shoot = getShoot(shootId) ?: return
        val userId = prefs.getString("user_id", null) ?: return
        val manifest = JSONObject()
            .put("shoot_id", shootId)
            .put("user_id", userId)
            .put("album_title", shoot.albumTitle)
            .put("settings_hash", "develop.auto-longEdge0-q90")
            .put(
                "files",
                JSONArray().apply {
                    files.forEach { f ->
                        put(
                            JSONObject()
                                .put("sha256", f.sha256)
                                .put("size_bytes", f.sizeBytes)
                                .put("original_name", f.originalName)
                                .put("storage_id", f.storageId)
                                .put("object_handle", f.objectHandle)
                                .put("capture_time_epoch_ms", f.captureTimeEpochMs),
                        )
                    }
                },
            )
        api.uploadManifest(shootId, manifest.toString())
        db.shootDao().upsert(shoot.copy(phase = "MANIFEST_UPLOADED"))
    }

    suspend fun submitShoot(shootId: String) {
        requireBackendReady()
        uploadManifestIfReady(shootId)
        api.finalizeShoot(shootId)
    }

    suspend fun refreshBackendStatus(shootId: String) {
        if (!canUseBackendApi()) return
        val status = api.fetchStatus(shootId)
        val shoot = getShoot(shootId) ?: return
        status.progress?.let { p ->
            prefs.edit().putString(
                "shoot_progress_$shootId",
                JSONObject(
                    mapOf(
                        "totalFiles" to p.totalFiles,
                        "renderedCount" to p.renderedCount,
                        "publishedCount" to p.publishedCount,
                        "uploadCompleteCount" to p.uploadCompleteCount,
                    ),
                ).toString(),
            ).apply()
        }
        db.shootDao().upsert(
            shoot.copy(
                state = status.state,
                phase = status.phase ?: status.state,
                backendState = status.state,
                albumId = status.albumId,
                actualComputeJson = status.actualCompute?.let {
                    JSONObject(mapOf("seconds" to it.seconds, "usd" to it.usd)).toString()
                },
            ),
        )
    }

    fun processingProgressJson(shootId: String): String? =
        prefs.getString("shoot_progress_$shootId", null)

    fun enqueueUploads(shootId: String) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf("shootId" to shootId))
            .addTag("upload-$shootId")
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            "upload-$shootId",
            ExistingWorkPolicy.KEEP,
            req,
        )
    }

    fun photosOAuthUrl(userId: String): String = api.photosOAuthStartUrl(userId)

    suspend fun retryFile(identityKey: String) {
        val f = db.fileDao().getFile(identityKey) ?: return
        db.fileDao().upsert(f.copy(uploadState = "PENDING", lastError = null))
    }

    private fun sha256File(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
