package com.photopipeline.app.data

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.photopipeline.app.api.ApiClient
import com.photopipeline.app.api.EstimateResponse
import com.photopipeline.app.core.fileIdentityKey
import com.photopipeline.app.mtp.CanonMtpRepository
import com.photopipeline.app.mtp.MtpRepository
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
) {
    private val appContext = context.applicationContext
    val mtp: MtpRepository = mtpOverride ?: CanonMtpRepository(context)
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

    suspend fun importFromCamera(shootId: String, destDir: File): Int {
        destDir.mkdirs()
        mtp.refreshConnection()
        val objects = mtp.listCr3Objects()
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
        return entities.size
    }

    suspend fun fetchAndStoreEstimate(shootId: String): EstimateResponse {
        val files = db.fileDao().observeForShoot(shootId).first()
        val totalBytes = files.sumOf { it.sizeBytes }.coerceAtLeast(1)
        val estimate = api.fetchEstimate(files.size.coerceAtLeast(1), totalBytes)
        val shoot = getShoot(shootId) ?: return estimate
        db.shootDao().upsert(
            shoot.copy(
                estimateJson = JSONObject(
                    mapOf(
                        "processingMinutes" to estimate.estimate.processingMinutes,
                        "totalShootUsd" to estimate.estimate.totalShootUsd,
                        "computeUsd" to estimate.estimate.computeUsd,
                        "s3StandardMonthUsd" to estimate.estimate.s3StandardMonthUsd,
                    ),
                ).toString(),
                phase = "ESTIMATE",
            ),
        )
        return estimate
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
        uploadManifestIfReady(shootId)
        api.finalizeShoot(shootId)
    }

    suspend fun refreshBackendStatus(shootId: String) {
        val status = api.fetchStatus(shootId)
        val shoot = getShoot(shootId) ?: return
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
