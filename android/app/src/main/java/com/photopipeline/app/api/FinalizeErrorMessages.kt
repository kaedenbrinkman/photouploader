package com.photopipeline.app.api

object FinalizeErrorMessages {
    fun friendly(
        ec2ErrorCode: String?,
        detail: String?,
        httpCode: Int? = null,
        responseState: String? = null,
        missingUploadCount: Int? = null,
    ): String {
        if (httpCode == 409) {
            missingUploadCount?.takeIf { it > 0 }?.let { n ->
                return "$n file(s) need re-upload before processing can start."
            }
            when (ec2ErrorCode) {
                "finalize_in_progress" ->
                    return "Server is already starting processing — checking status…"
                "upload_incomplete" ->
                    return "Some files are still missing on the server — finish upload, then retry."
            }
            responseState?.uppercase()?.let { st ->
                when (st) {
                    "UPLOADING" -> return "Upload is not complete on the server yet — finish upload, then retry."
                    "READY_TO_PROCESS" -> return "Processing could not start yet — try again in a moment."
                    "PROCESSING", "LAUNCHING" ->
                        return "Server is already processing this shoot — checking status…"
                    "FAILED" -> return detail?.take(280) ?: "Previous processing failed — tap Retry."
                }
            }
        }
        mapServerWorkerMessage(detail)?.let { return it }
        when (ec2ErrorCode) {
            "PendingVerification" ->
                return "AWS account verification pending — try again later."
            "ServiceLinkedRoleCreationNotPermitted" ->
                return "AWS needs a one-time Spot service role — ask your admin or retry after IAM is fixed."
            "InsufficientInstanceCapacity" ->
                return "AWS capacity is temporarily unavailable in this region — try again later."
            "worker_artifacts_not_configured" ->
                return "Server worker bundle is not deployed yet — contact the operator."
            "worker_artifacts_missing" ->
                return "Worker release files are missing or checksums do not match — try again after deploy."
            "worker_sha_ssm_missing" ->
                return "Worker SHA256 parameters are not configured in SSM — contact the operator."
            "finalize_in_progress" ->
                return "Server is already starting processing — checking status…"
        }
        if (!detail.isNullOrBlank()) {
            mapServerWorkerMessage(detail)?.let { return it }
            if (detail.equals("timeout", ignoreCase = true)) {
                return FinalizeStartRecovery.STATUS_LINE_CHECKING
            }
            return detail.take(280)
        }
        return when (httpCode) {
            504, 502 -> FinalizeStartRecovery.STATUS_LINE_CHECKING
            503 -> "Processing could not start yet — try again later."
            else -> "Processing could not start."
        }
    }

    /** Map worker [lastError] text from DynamoDB (includes ENOSPC, render failures, etc.). */
    fun mapServerWorkerMessage(detail: String?): String? {
        if (detail.isNullOrBlank()) return null
        val lower = detail.lowercase()
        when {
            lower.contains("no space left on device") ||
                lower.contains("errno 28") ||
                lower.contains("enospc") ->
                return "Server ran out of disk space while processing. Retry after deploy — worker disk is sized per shoot."
            lower.contains("timeout") && lower.contains("worker log") ->
                return detail.take(280)
        }
        return null
    }
}
