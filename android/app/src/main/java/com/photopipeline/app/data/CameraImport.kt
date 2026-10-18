package com.photopipeline.app.data

import com.photopipeline.app.mtp.MtpObjectInfo

sealed class CameraImportResult {
    data class Listed(val objects: List<MtpObjectInfo>, val statusMessage: String) : CameraImportResult()
    data class Imported(val newCount: Int, val totalOnShoot: Int, val statusMessage: String) : CameraImportResult()
    data class Error(val stage: String, val userMessage: String) : CameraImportResult()
}
