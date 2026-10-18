package com.photopipeline.app.data

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.photopipeline.app.api.ApiClient
import com.photopipeline.app.api.NotSignedInException
import com.photopipeline.app.core.AppDefaults
import com.photopipeline.app.core.PipelineLog
import com.photopipeline.app.core.cameraFileIdentityKey
import com.photopipeline.app.core.metadataUploadSha256
import com.photopipeline.app.pricing.LocalPricingEstimator
import com.photopipeline.app.mtp.MtpRepository
import com.photopipeline.app.mtp.MtpObjectInfo
import com.photopipeline.app.mtp.UsbAccessState
import com.photopipeline.app.mtp.UsbCameraManager
import com.photopipeline.app.mtp.UsbDebugLog
import com.photopipeline.app.upload.UploadPart
import com.photopipeline.app.upload.UploadParts
import com.photopipeline.app.upload.UploadPreferences
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
    private val uploadPreferences: UploadPreferences,
) {
    private val appContext = context.applicationContext
    val mtp: MtpRepository = mtpOverride ?: error("MtpRepository required")
    private val prefs = appContext.getSharedPreferences("photopipeline", Context.MODE_PRIVATE)

    val api: ApiClient = ApiClient(
        baseUrlProvider = { apiBaseUrl() },
        sessionTokenProvider = { prefs.getString("session_token", null) },
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

    suspend fun markFilePaused(identityKey: String, reason: String) = patchFile(identityKey) {
        it.copy(uploadState = "PAUSED", lastError = reason)
    }

    suspend fun markFileUploading(identityKey: String) = patchFile(identityKey) {
        it.copy(uploadState = "UPLOADING", lastError = null)
    }

    suspend fun incrementRetry(identityKey: String, error: String) = patchFile(identityKey) {
        it.copy(retryCount = it.retryCount + 1, lastError = error)
    }

    suspend fun updateFileContentSha256(identityKey: String, sha256: String, parts: List<UploadPart>) =
        patchFile(identityKey) {
            it.copy(sha256 = sha256, uploadPartsJson = UploadParts.toJson(parts), uploadedBytes = it.sizeBytes)
        }

    suspend fun countUploadedFiles(shootId: String): Int =
        db.fileDao().observeForShoot(shootId).first().count { it.uploadState == "UPLOADED" }

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

    fun isGoogleAccountLinked(): Boolean = !prefs.getString("session_token", null).isNullOrBlank()

    fun googleUserId(): String? = prefs.getString("google_sub", null)

    fun googleEmail(): String? = prefs.getString("google_email", null)

    fun googleWebClientId(): String =
        prefs.getString("google_web_client_id", AppDefaults.GOOGLE_WEB_CLIENT_ID)
            ?.trim()
            .orEmpty()
            .ifBlank { AppDefaults.GOOGLE_WEB_CLIENT_ID }

    fun apiBaseUrl(): String =
        prefs.getString("api_base", AppDefaults.API_BASE_URL)!!.trim().trimEnd('/')

    fun saveSettings(apiBase: String, googleWebClientId: String?) {
        prefs.edit()
            .putString("api_base", apiBase.trim().trimEnd('/'))
            .putString("google_web_client_id", googleWebClientId?.trim()?.ifBlank { null })
            .apply()
    }

    suspend fun completeGoogleSignIn(serverAuthCode: String) {
        val resp = api.exchangeGoogleAuth(serverAuthCode)
        prefs.edit()
            .putString("session_token", resp.sessionToken)
            .putString("google_sub", resp.sub)
            .putString("google_email", resp.email)
            .apply()
        PipelineLog.d("auth", "Signed in as ${resp.sub} gphotos=${resp.gphotosLinked}")
    }

    fun signOutGoogle() {
        prefs.edit()
            .remove("session_token")
            .remove("google_sub")
            .remove("google_email")
            .apply()
    }

    fun isBackendConfigured(): Boolean = apiBaseUrl().startsWith("http")

    fun canUseBackendApi(): Boolean = isBackendConfigured() && isGoogleAccountLinked()

    fun backendGateMessage(): String = when {
        !isBackendConfigured() -> "Backend not configured — set your API base URL in Settings."
        googleWebClientId().isBlank() ->
            "Set Google Web OAuth client ID in Settings (see gphotos/GOOGLE_OAUTH_ANDROID.md)."
        !isGoogleAccountLinked() -> "Tap Connect with Google before upload or submit."
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

    suspend fun registerSelectedCameraFiles(
        shootId: String,
        only: List<MtpObjectInfo>,
        localCopy: Boolean = false,
        destDir: File? = null,
    ): CameraImportResult {
        ensureUsbReady()?.let { return it }
        if (only.isEmpty()) {
            return CameraImportResult.Error(stage = "import", userMessage = "Nothing selected.")
        }
        val existingKeys = db.fileDao().observeForShoot(shootId).first().map { it.identityKey }.toSet()
        val entities = mutableListOf<FileEntity>()
        for (obj in only) {
            val identityKey = cameraFileIdentityKey(
                obj.storageId,
                obj.handle,
                obj.sizeBytes,
                obj.captureTimeEpochMs,
            )
            if (identityKey in existingKeys) continue
            val uploadRef = metadataUploadSha256(
                obj.storageId,
                obj.handle,
                obj.sizeBytes,
                obj.captureTimeEpochMs,
            )
            var localPath: String? = null
            var sha256 = ""
            var uploadSource = "CAMERA_STREAM"
            if (localCopy && destDir != null) {
                destDir.mkdirs()
                val path = File(destDir, obj.filename).absolutePath
                sha256 = if (File(path).length() == obj.sizeBytes && File(path).exists()) {
                    sha256File(File(path))
                } else {
                    mtp.importObject(obj, path) { }
                }
                localPath = path
                uploadSource = "LOCAL"
            }
            entities += FileEntity(
                identityKey = identityKey,
                shootId = shootId,
                sha256 = sha256.ifBlank { uploadRef },
                originalName = obj.filename,
                sizeBytes = obj.sizeBytes,
                storageId = obj.storageId,
                objectHandle = obj.handle,
                captureTimeEpochMs = obj.captureTimeEpochMs,
                localPath = localPath,
                uploadSource = uploadSource,
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
            entities.isEmpty() -> "No new files to add (${only.size} already in this shoot)."
            localCopy -> "Copied ${entities.size} file(s) to phone (local mode)."
            else -> "Queued ${entities.size} file(s) for direct camera→S3 upload (no full copy on phone)."
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
        val measuredBps = uploadPreferences.lastMeasuredUploadBps
        val uploadMinutes = if (measuredBps > 0) {
            (totalBytes * 8.0 / measuredBps / 60.0).coerceAtLeast(0.1)
        } else {
            null
        }
        val useAccel = uploadPreferences.transferAcceleration
        val stored = if (canUseBackendApi()) {
            val remote = runCatching { api.fetchEstimate(imageCount, totalBytes, useAccel) }
            if (remote.isSuccess) {
                StoredEstimate(remote.getOrThrow(), EstimateSource.BACKEND)
            } else {
                val err = remote.exceptionOrNull()
                PipelineLog.w(
                    "estimate",
                    "Backend estimate failed (${err?.message}); using local pricing.json",
                    err,
                )
                StoredEstimate(
                    localPricing.estimateShoot(imageCount, totalBytes, useAccel),
                    EstimateSource.LOCAL,
                )
            }
        } else {
            PipelineLog.d(
                "estimate",
                "Using local pricing (signedIn=${isGoogleAccountLinked()} configured=${isBackendConfigured()})",
            )
            StoredEstimate(
                localPricing.estimateShoot(imageCount, totalBytes, useAccel),
                EstimateSource.LOCAL,
            )
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
                            "transferAccelerationUsd" to stored.response.estimate.transferAccelerationUsd,
                            "source" to stored.source.name,
                            "notes" to notes,
                            "uploadMinutesMeasured" to uploadMinutes,
                            "measuredUploadMbps" to if (measuredBps > 0) measuredBps * 8.0 / 1_000_000.0 else null,
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
        val userId = prefs.getString("google_sub", null) ?: return
        val manifest = JSONObject()
            .put("shoot_id", shootId)
            .put("user_id", userId)
            .put("album_title", shoot.albumTitle)
            .put("settings_hash", "develop.auto-longEdge0-q90")
            .put(
                "files",
                JSONArray().apply {
                    files.forEach { f ->
                        val uploadRef = metadataUploadSha256(
                            f.storageId,
                            f.objectHandle,
                            f.sizeBytes,
                            f.captureTimeEpochMs,
                        )
                        val ext = if (f.originalName.uppercase().endsWith(".JPG")) ".JPG" else ".CR3"
                        val s3Key = "shoots/$shootId/raw/${uploadRef.lowercase()}$ext"
                        put(
                            JSONObject()
                                .put("sha256", f.sha256)
                                .put("s3_key", s3Key)
                                .put("size_bytes", f.sizeBytes)
                                .put("original_name", f.originalName)
                                .put("storage_id", f.storageId)
                                .put("object_handle", f.objectHandle)
                                .put("capture_time_epoch_ms", f.captureTimeEpochMs),
                        )
                    }
                },
            )
        api.uploadManifest(shootId, manifest.toString(), uploadPreferences.transferAcceleration)
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
