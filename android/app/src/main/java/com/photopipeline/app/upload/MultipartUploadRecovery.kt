package com.photopipeline.app.upload

import com.photopipeline.app.api.ApiRequestException

object MultipartUploadRecovery {
    private val recoverableCompleteErrors = setOf(
        "EntityTooSmall",
        "InvalidPart",
        "InvalidPartOrder",
    )

    fun isRecoverableCompleteError(e: ApiRequestException): Boolean {
        val code = e.ec2ErrorCode ?: return false
        return code in recoverableCompleteErrors
    }
}
