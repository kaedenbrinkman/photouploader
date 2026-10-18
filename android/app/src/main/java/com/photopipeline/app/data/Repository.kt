package com.photopipeline.app.data

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.photopipeline.app.upload.UploadMetricsStore
import com.photopipeline.app.api.ApiClient
import com.photopipeline.app.api.NotSignedInException
import com.photopipeline.app.core.AppDefaults
import com.photopipeline.app.core.PipelineLog
import com.photopipeline.app.api.ApiRequestException
import com.photopipeline.app.core.cameraFileIdentityKey
import com.photopipeline.app.core.cameraFileMatchKey
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
import okhttp3.OkHttpClient
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
    private val googleHttp = OkHttpClient.Builder().build()

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

    fun googleDisplayName(): String? = prefs.getString("google_name", null)

    fun googlePictureUrl(): String? = prefs.getString("google_picture_url", null)

    /** Cached avatar file preferred for Coil. */
    fun googlePictureModel(): Any? {
        val cache = java.io.File(appContext.filesDir, "google_avatar.jpg")
        if (cache.exists() && cache.length() > 0L) return cache
        return googlePictureUrl()?.takeIf { it.isNotBlank() }
    }

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

    suspend fun completeGoogleSignIn(serverAuthCode: String, googleAccessToken: String? = null) {
        val resp = api.exchangeGoogleAuth(serverAuthCode)
        var name: String? = null
        var picture: String? = null
        if (!googleAccessToken.isNullOrBlank()) {
            fetchGoogleUserInfo(googleAccessToken)?.let { info ->
                name = info.first
                picture = info.second
            }
        }
        prefs.edit()
            .putString("session_token", resp.sessionToken)
            .putString("google_sub", resp.sub)
            .putString("google_email", resp.email)
            .putString("google_name", name)
            .putString("google_picture_url", picture)
            .apply()
        if (!picture.isNullOrBlank()) {
            cacheGoogleAvatar(picture!!)
        }
        PipelineLog.d("auth", "Signed in as ${resp.sub} gphotos=${resp.gphotosLinked}")
    }

    private suspend fun fetchGoogleUserInfo(accessToken: String): Pair<String?, String?>? {
        return runCatching {
            val req = okhttp3.Request.Builder()
                .url("https://www.googleapis.com/oauth2/v3/userinfo")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            googleHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val json = org.json.JSONObject(resp.body!!.string())
                json.optString("name").ifBlank { null } to json.optString("picture").ifBlank { null }
            }
        }.getOrNull()
    }

    private suspend fun cacheGoogleAvatar(pictureUrl: String) {
        runCatching {
            val req = okhttp3.Request.Builder().url(pictureUrl).get().build()
            googleHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching
                val body = resp.body?.bytes() ?: return@runCatching
                java.io.File(appContext.filesDir, "google_avatar.jpg").writeBytes(body)
            }
        }.onFailure { e ->
            PipelineLog.d("auth", "avatar cache failed: ${e.message}")
        }
    }

    fun signOutGoogle() {
        prefs.edit()
            .remove("session_token")
            .remove("google_sub")
            .remove("google_email")
            .remove("google_name")
            .remove("google_picture_url")
            .apply()
        runCatching { java.io.File(appContext.filesDir, "google_avatar.jpg").delete() }
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

    private fun serverShootRegisteredKey(shootId: String) = "server_shoot_$shootId"

    fun isServerShootRegistered(shootId: String): Boolean =
        prefs.getBoolean(serverShootRegisteredKey(shootId), false)

    suspend fun ensureServerShoot(shootId: String) {
        requireBackendReady()
        if (isServerShootRegistered(shootId)) return
        val shoot = getShoot(shootId) ?: error("shoot missing")
        runCatching { api.ensureServerShoot(shootId, shoot.albumTitle) }
            .onSuccess {
                prefs.edit().putBoolean(serverShootRegisteredKey(shootId), true).apply()
            }
            .onFailure { e ->
                if (e is ApiRequestException && e.httpCode == 409) {
                    prefs.edit().putBoolean(serverShootRegisteredKey(shootId), true).apply()
                    return
                }
                throw e
            }
    }

    /**
     * USB permission, MTP open, and rematch of camera-stream files when object handles change between sessions.
     */
    suspend fun prepareCameraForUpload(shootId: String): PrepareCameraResult {
        val pending = getPendingFiles(shootId)
        val needsCamera = pending.any { it.isCameraStreamPending() }
        if (!needsCamera) return PrepareCameraResult.Ready

        val manager = usb ?: return PrepareCameraResult.Error("USB manager unavailable")
        // Prefer the singleton's open session (same as listCr3Objects) before a full re-probe.
        var mtpOpen = manager.openMtpDevice()
        if (mtpOpen == null) {
            ensureUsbReady()?.let { err ->
                return PrepareCameraResult.Error(err.userMessage)
            }
            mtpOpen = manager.openMtpDevice()
        }
        if (mtpOpen == null) {
            val state = manager.accessState.value
            val msg = when (state) {
                UsbAccessState.PERMISSION_REQUIRED ->
                    "USB permission required — tap Connect on the camera step or allow access when prompted."
                UsbAccessState.PERMISSION_DENIED -> "USB permission denied for the camera."
                UsbAccessState.NO_DEVICE -> "No camera detected — reconnect USB."
                else -> "Could not open MTP session (state=$state)."
            }
            PipelineLog.w("upload", "prepareCameraForUpload: $msg")
            return PrepareCameraResult.Error(msg)
        }
        this.mtp.refreshConnection()
        val onCamera = runCatching { this.mtp.listCr3Objects() }.getOrElse { e ->
            PipelineLog.w("upload", "prepareCameraForUpload list failed", e)
            return PrepareCameraResult.Error("Failed to list camera files: ${e.message ?: "unknown"}")
        }
        if (onCamera.isEmpty()) {
            return PrepareCameraResult.Error("Camera connected but no media files found to match.")
        }
        val byMatchKey = onCamera.groupBy { cameraFileMatchKey(it.filename, it.sizeBytes, it.captureTimeEpochMs) }
        var rematched = 0
        for (file in pending.filter { it.isCameraStreamPending() }) {
            val key = cameraFileMatchKey(file.originalName, file.sizeBytes, file.captureTimeEpochMs)
            val candidates = byMatchKey[key].orEmpty()
            val obj = when {
                candidates.size == 1 -> candidates.first()
                candidates.isEmpty() -> {
                    val byNameSize = onCamera.filter {
                        it.filename.equals(file.originalName, ignoreCase = true) && it.sizeBytes == file.sizeBytes
                    }
                    byNameSize.singleOrNull()
                }
                else -> candidates.firstOrNull {
                    it.filename.equals(file.originalName, ignoreCase = true)
                } ?: candidates.first()
            } ?: return PrepareCameraResult.Error(
                "Camera file not found: ${file.originalName} (${file.sizeBytes} bytes). Re-import from the camera.",
            )
            val handleChanged = obj.handle != file.objectHandle || obj.storageId != file.storageId
            val newIdentity = cameraFileIdentityKey(
                obj.storageId,
                obj.handle,
                obj.sizeBytes,
                obj.captureTimeEpochMs,
            )
            val newUploadRef = metadataUploadSha256(
                obj.storageId,
                obj.handle,
                obj.sizeBytes,
                obj.captureTimeEpochMs,
            )
            val updated = file.copy(
                storageId = obj.storageId,
                objectHandle = obj.handle,
                sha256 = if (handleChanged || file.sha256.isBlank()) newUploadRef else file.sha256,
                s3UploadId = if (handleChanged) null else file.s3UploadId,
                s3Key = if (handleChanged) null else file.s3Key,
                uploadPartsJson = if (handleChanged) "[]" else file.uploadPartsJson,
                uploadState = if (file.uploadState == "PAUSED") "PENDING" else file.uploadState,
                lastError = null,
            )
            if (newIdentity != file.identityKey) {
                db.fileDao().delete(file.identityKey)
                db.fileDao().upsert(updated.copy(identityKey = newIdentity))
            } else {
                db.fileDao().upsert(updated)
            }
            if (handleChanged) rematched++
        }
        if (rematched > 0) {
            PipelineLog.i("upload", "Rematched $rematched camera file(s) after MTP handle change")
        }
        return PrepareCameraResult.Ready
    }

    private fun FileEntity.isCameraStreamPending(): Boolean =
        uploadSource == "CAMERA_STREAM" || localPath.isNullOrBlank()

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
                            "imageCount" to stored.response.estimate.imageCount,
                            "processingMinutes" to stored.response.estimate.processingMinutes,
                            "totalShootUsd" to stored.response.estimate.totalShootUsd,
                            "computeUsd" to stored.response.estimate.computeUsd,
                            "s3StandardMonthUsd" to stored.response.estimate.s3StandardMonthUsd,
                            "s3DeepArchiveMonthUsd" to stored.response.estimate.s3DeepArchiveMonthUsd,
                            "requestsUsd" to stored.response.estimate.requestsUsd.let {
                                if (it.isNaN() || it.isInfinite()) 0.0 else it
                            },
                            "transferAccelerationUsd" to stored.response.estimate.transferAccelerationUsd,
                            "perFileUsd" to stored.response.estimate.totalShootUsd / imageCount,
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
                        val s3Key = f.s3Key?.takeIf { it.isNotBlank() }
                            ?: "shoots/$shootId/raw/${uploadRef.lowercase()}$ext"
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

    fun getFinalizeError(shootId: String): String? =
        prefs.getString(finalizeErrorKey(shootId), null)

    fun clearFinalizeError(shootId: String) {
        prefs.edit().remove(finalizeErrorKey(shootId)).apply()
    }

    private fun setFinalizeError(shootId: String, message: String) {
        prefs.edit().putString(finalizeErrorKey(shootId), message).apply()
    }

    private fun finalizeErrorKey(shootId: String): String = "shoot_finalize_error_$shootId"

    /** Manifest + finalize only — no camera / MTP (files already in S3). */
    suspend fun finalizeShootForProcessing(shootId: String) {
        requireBackendReady()
        ensureServerShoot(shootId)
        uploadManifestIfReady(shootId)
        try {
            api.finalizeShoot(shootId)
            clearFinalizeError(shootId)
        } catch (e: ApiRequestException) {
            val msg = e.message ?: "Finalize failed (HTTP ${e.httpCode ?: "?"})"
            setFinalizeError(shootId, msg)
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: "Finalize failed"
            setFinalizeError(shootId, msg)
            throw e
        }
    }

    suspend fun submitShoot(shootId: String) = finalizeShootForProcessing(shootId)

    suspend fun refreshBackendStatus(shootId: String): com.photopipeline.app.api.ShootStatusResponse? {
        if (!canUseBackendApi()) return null
        val status = api.fetchStatus(shootId)
        val shoot = getShoot(shootId) ?: return status
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
                actualComputeJson = status.actualCompute?.let { ac ->
                    val sec = ac.seconds ?: 0
                    val usd = ac.usd?.takeIf { !it.isNaN() && !it.isInfinite() } ?: 0.0
                    JSONObject(mapOf("seconds" to sec, "usd" to usd)).toString()
                },
            ),
        )
        return status
    }

    fun processingProgressJson(shootId: String): String? =
        prefs.getString("shoot_progress_$shootId", null)

    fun uploadWorkName(shootId: String): String = "upload-$shootId"

    suspend fun cancelUploadForShoot(shootId: String) {
        WorkManager.getInstance(appContext).cancelUniqueWork(uploadWorkName(shootId))
        val files = db.fileDao().observeForShoot(shootId).first()
        val accel = uploadPreferences.transferAcceleration
        for (f in files) {
            if (
                !f.s3UploadId.isNullOrBlank() &&
                !f.s3Key.isNullOrBlank() &&
                f.uploadState != "UPLOADED"
            ) {
                runCatching { api.abortMultipart(shootId, f.s3UploadId!!, f.s3Key!!, accel) }
                    .onFailure { e ->
                        PipelineLog.w("upload", "abort multipart ${f.originalName}: ${e.message}", e)
                    }
            }
            if (f.uploadState != "UPLOADED") {
                db.fileDao().upsert(
                    f.copy(
                        uploadState = "PENDING",
                        s3UploadId = null,
                        s3Key = null,
                        uploadPartsJson = "[]",
                        uploadedBytes = 0,
                        uploadSpeedBps = 0,
                        lastError = null,
                    ),
                )
            }
        }
        UploadMetricsStore.clear()
        PipelineLog.i("upload", "Cancelled upload work for shoot $shootId")
    }

    suspend fun deleteShootCompletely(shootId: String) {
        cancelUploadForShoot(shootId)
        if (canUseBackendApi() && isServerShootRegistered(shootId)) {
            api.deleteShoot(shootId)
        }
        db.fileDao().deleteForShoot(shootId)
        db.shootDao().delete(shootId)
        prefs.edit()
            .remove(serverShootRegisteredKey(shootId))
            .remove("shoot_progress_$shootId")
            .apply()
        PipelineLog.i("upload", "Deleted shoot $shootId locally and on server")
    }

    suspend fun enqueueUploads(shootId: String) {
        requireBackendReady()
        ensureServerShoot(shootId)
        when (val prep = prepareCameraForUpload(shootId)) {
            is PrepareCameraResult.Error -> error(prep.message)
            PrepareCameraResult.Ready -> Unit
        }
        val pending = getPendingFiles(shootId)
        val usesCameraStream = pending.any {
            it.uploadSource == "CAMERA_STREAM" || it.localPath.isNullOrBlank()
        }
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(
                workDataOf(
                    "shootId" to shootId,
                    UploadWorker.KEY_USES_CAMERA_STREAM to usesCameraStream,
                ),
            )
            .addTag("upload-$shootId")
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            "upload-$shootId",
            ExistingWorkPolicy.REPLACE,
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

sealed class PrepareCameraResult {
    data object Ready : PrepareCameraResult()
    data class Error(val message: String) : PrepareCameraResult()
}
