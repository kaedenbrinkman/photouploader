package com.photopipeline.app.ui

import com.photopipeline.app.data.ShootEntity

fun serverUploadsComplete(progress: ProcessingProgress?): Boolean {
    if (progress == null) return false
    return progress.totalFiles > 0 && progress.uploadCompleteCount >= progress.totalFiles
}

fun serverUploadsComplete(progressJson: String?): Boolean =
    serverUploadsComplete(parseProcessingProgress(progressJson))

fun shouldOfferProcessingRetry(
    shoot: ShootEntity,
    progressJson: String?,
    serverRegistered: Boolean,
    finalizeState: FinalizeState,
    finalizeError: String?,
    serverLastError: String?,
    localAllUploaded: Boolean,
    staleFinalizeOnly: Boolean,
    uploadInProgress: Boolean,
): Boolean {
    if (finalizeState == FinalizeState.InProgress) return false
    if (staleFinalizeOnly) return false

    val uploadsReady = serverUploadsComplete(progressJson) ||
        (localAllUploaded && serverRegistered)

    if (!uploadsReady) return false

    if (finalizeState == FinalizeState.Failed || !finalizeError.isNullOrBlank()) return true
    if (shoot.state == "PARTIAL") return true
    if (shoot.state == "DONE") {
        parseProcessingProgress(progressJson)?.let { progress ->
            val published = progress.publishedPhotos + progress.publishedVideos
            val total = progress.photoCount + progress.videoCount
            if (published == 0 || (total > 0 && published < total)) return true
        }
    }
    if (shoot.state == "FAILED" || shoot.state == "READY_TO_PROCESS") return true
    if (!shoot.backendState.isNullOrBlank() && shoot.backendState in setOf("FAILED", "READY_TO_PROCESS")) {
        return true
    }
    if (shoot.state == "PROCESSING" && !serverLastError.isNullOrBlank()) return true
    if (shoot.state == "PROCESSING" && shoot.backendState == "READY_TO_PROCESS") return true
    return shoot.state in setOf("LOCAL", "IMPORTING") &&
        shoot.phase.contains("MANIFEST", ignoreCase = true) &&
        !uploadInProgress
}
