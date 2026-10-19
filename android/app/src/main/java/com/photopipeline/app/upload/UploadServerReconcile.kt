package com.photopipeline.app.upload

import com.photopipeline.app.api.FileStatusDto
import com.photopipeline.app.api.ProgressDto
import com.photopipeline.app.core.metadataUploadSha256
import com.photopipeline.app.data.FileEntity

object UploadServerReconcile {
    private val serverUploadedStates = setOf("UPLOADED", "GP_CREATED", "RENDERED", "PUBLISHED")

    fun serverUploadedShaKeys(files: List<FileStatusDto>): Set<String> =
        files.filter { f ->
            val state = f.state.orEmpty()
            state in serverUploadedStates && !f.sha256.isNullOrBlank()
        }.map { f -> f.sha256!!.lowercase() }
            .toSet()

    fun localLookupKeys(file: FileEntity): Set<String> {
        val keys = linkedSetOf<String>()
        val uploadRef = metadataUploadSha256(
            file.storageId,
            file.objectHandle,
            file.sizeBytes,
            file.captureTimeEpochMs,
        ).lowercase()
        keys.add(uploadRef)
        if (file.sha256.isNotBlank()) keys.add(file.sha256.lowercase())
        return keys
    }

    fun shouldMarkLocalUploaded(
        file: FileEntity,
        serverUploadedShas: Set<String>,
        serverFiles: List<FileStatusDto>,
    ): Boolean {
        if (file.uploadState == "UPLOADED") return false
        if (localLookupKeys(file).any { it in serverUploadedShas }) return true
        val name = file.originalName
        return serverFiles.any { sf ->
            val state = sf.state.orEmpty()
            state in serverUploadedStates &&
                !sf.originalName.isNullOrBlank() &&
                sf.originalName == name &&
                (sf.sizeBytes == null || sf.sizeBytes == file.sizeBytes)
        }
    }

    fun serverReportsAllUploaded(
        localFileCount: Int,
        serverFiles: List<FileStatusDto>,
        progress: ProgressDto?,
    ): Boolean {
        if (localFileCount <= 0 || serverFiles.isEmpty()) return false
        if (progress != null &&
            progress.uploadCompleteCount >= localFileCount &&
            progress.totalFiles >= localFileCount
        ) {
            return true
        }
        return serverFiles.size >= localFileCount &&
            serverFiles.all { f -> f.state.orEmpty() in serverUploadedStates }
    }
}
