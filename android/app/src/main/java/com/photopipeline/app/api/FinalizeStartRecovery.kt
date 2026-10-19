package com.photopipeline.app.api

import java.net.SocketTimeoutException

object FinalizeStartRecovery {
    const val STATUS_LINE_CHECKING =
        "Server is taking longer to start, checking…"

    fun isMaybeStartedFailure(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            when (t) {
                is SocketTimeoutException -> return true
                is ApiRequestException -> {
                    if (t.httpCode == 504 || t.httpCode == 502 || t.httpCode == 503) return true
                    if (t.ec2ErrorCode == "finalize_in_progress") return true
                }
            }
            val msg = t.message?.lowercase().orEmpty()
            if (msg == "timeout" || msg.contains("timed out")) return true
            t = t.cause
        }
        return false
    }

    fun processingAppearsStarted(status: ShootStatusResponse): Boolean {
        val state = status.state?.uppercase().orEmpty()
        val phase = status.phase?.uppercase().orEmpty()
        if (state == "PROCESSING" || state == "DONE") return true
        if (phase.contains("LAUNCH") || phase.contains("INSTANCE") || phase.contains("BOOTSTRAP")) {
            return true
        }
        if (phase == "RENDERING" || phase == "PUBLISHING") return true
        return false
    }

    fun friendlyFailureMessage(e: Throwable): String {
        if (isMaybeStartedFailure(e)) {
            return "Server is taking longer to start. Pull down to refresh or tap Retry."
        }
        return when (e) {
            is ApiRequestException -> e.message ?: FinalizeErrorMessages.friendly(e.ec2ErrorCode, null, e.httpCode)
            else -> e.message?.takeIf { it.isNotBlank() && !it.equals("timeout", ignoreCase = true) }
                ?: "Processing could not start."
        }
    }
}
