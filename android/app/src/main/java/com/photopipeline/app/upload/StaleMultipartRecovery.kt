package com.photopipeline.app.upload

import com.photopipeline.app.api.ApiClient
import com.photopipeline.app.api.ApiRequestException
import com.photopipeline.app.api.FileStatusDto
import com.photopipeline.app.core.metadataUploadSha256
import com.photopipeline.app.data.FileEntity
import com.photopipeline.app.media.MediaKinds
import java.io.IOException

/** Stale S3 multipart uploadId or presign against an already-finalized object. */
class StaleMultipartUploadException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

object StaleMultipartRecovery {
    enum class Outcome {
        /** Object is on S3 / server says UPLOADED; caller should mark local file done. */
        AlreadyOnServer,

        /** Cleared stale uploadId; caller should retry with fresh multipart. */
        FreshMultipart,
    }

    fun isStaleMultipartError(e: ApiRequestException): Boolean {
        if (e.httpCode == 404) return true
        val code = e.ec2ErrorCode?.lowercase() ?: return false
        return code == "nosuchupload" ||
            code == "no_such_upload" ||
            code.contains("nosuchupload")
    }

    fun isStaleMultipartFailure(e: Throwable): Boolean =
        e is StaleMultipartUploadException ||
            (e is ApiRequestException && isStaleMultipartError(e))

    suspend fun resolve(
        shootId: String,
        file: FileEntity,
        api: ApiClient,
        transferAcceleration: Boolean,
        serverFiles: List<FileStatusDto>?,
        headObject: suspend (String) -> ApiClient.HeadObjectResult,
        markUploaded: suspend (FileEntity) -> Unit,
        resetMultipart: suspend (FileEntity) -> FileEntity,
    ): Pair<Outcome, FileEntity> {
        if (serverFiles != null && shouldMarkFromServer(file, serverFiles)) {
            markUploaded(file)
            return Outcome.AlreadyOnServer to file.copy(
                uploadState = "UPLOADED",
                uploadedBytes = file.sizeBytes,
            )
        }
        val key = s3KeyForFile(shootId, file)
        val head = runCatching { headObject(key) }.getOrNull()
        if (head?.exists == true && head.contentLength == file.sizeBytes) {
            markUploaded(file)
            return Outcome.AlreadyOnServer to file.copy(
                uploadState = "UPLOADED",
                uploadedBytes = file.sizeBytes,
            )
        }
        val cleared = resetMultipart(file)
        return Outcome.FreshMultipart to cleared
    }

    fun shouldMarkFromServer(file: FileEntity, serverFiles: List<FileStatusDto>): Boolean {
        val uploadedShas = UploadServerReconcile.serverUploadedShaKeys(serverFiles)
        return UploadServerReconcile.shouldMarkLocalUploaded(file, uploadedShas, serverFiles)
    }

    fun s3KeyForFile(shootId: String, file: FileEntity): String {
        file.s3Key?.takeIf { it.isNotBlank() }?.let { return it }
        val uploadRef = metadataUploadSha256(
            file.storageId,
            file.objectHandle,
            file.sizeBytes,
            file.captureTimeEpochMs,
        ).lowercase()
        val ext = rawExtension(file.originalName)
        return "shoots/$shootId/raw/$uploadRef$ext"
    }

    private fun rawExtension(originalName: String): String {
        val u = originalName.uppercase()
        return when {
            u.endsWith(".JPG") || u.endsWith(".JPEG") -> ".JPG"
            u.endsWith(".CR2") -> ".CR2"
            u.endsWith(".MP4") -> ".MP4"
            u.endsWith(".MOV") -> ".MOV"
            u.endsWith(".M4V") -> ".M4V"
            u.endsWith(".HEIC") -> ".HEIC"
            MediaKinds.isVideoFileName(originalName) -> ".MP4"
            else -> ".CR3"
        }
    }
}
