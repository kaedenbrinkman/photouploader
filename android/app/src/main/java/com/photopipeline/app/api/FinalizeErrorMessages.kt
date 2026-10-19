package com.photopipeline.app.api

object FinalizeErrorMessages {
    fun friendly(ec2ErrorCode: String?, detail: String?, httpCode: Int? = null): String {
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
}
