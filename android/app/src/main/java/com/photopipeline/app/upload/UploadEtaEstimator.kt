package com.photopipeline.app.upload

import kotlin.math.max

/**
 * Smoothed ETA from remaining bytes and a blend of windowed and session-average speed.
 */
object UploadEtaEstimator {
    private const val MIN_ELAPSED_MS = 5_000L
    private const val MIN_BYTES = 10_000_000L

    private var smoothedSeconds: Double? = null

    fun reset() {
        smoothedSeconds = null
    }

    fun canEstimate(sessionBytes: Long, sessionElapsedMs: Long): Boolean =
        sessionElapsedMs >= MIN_ELAPSED_MS && sessionBytes >= MIN_BYTES

    fun estimateSeconds(
        remainingBytes: Long,
        currentBps: Long,
        averageBps: Long,
        sessionBytes: Long = MIN_BYTES,
        sessionElapsedMs: Long = MIN_ELAPSED_MS,
    ): Long? {
        if (remainingBytes <= 0) {
            smoothedSeconds = 0.0
            return 0L
        }
        if (!canEstimate(sessionBytes, sessionElapsedMs)) {
            return null
        }
        val avg = averageBps.coerceAtLeast(0)
        val cur = currentBps.coerceAtLeast(0)
        val effective = when {
            cur > 0 && avg > 0 -> (0.5 * cur + 0.5 * avg).toLong().coerceAtLeast(1L)
            cur > 0 -> cur.coerceAtLeast(1L)
            avg > 0 -> avg.coerceAtLeast(1L)
            else -> return null
        }
        val raw = remainingBytes.toDouble() / effective.toDouble()
        smoothedSeconds = when (val prev = smoothedSeconds) {
            null -> raw
            else -> prev * 0.85 + raw * 0.15
        }
        return smoothedSeconds?.toLong()?.coerceAtLeast(0L)
    }

    fun formatEta(seconds: Long?): String {
        if (seconds == null) return "Estimating…"
        if (seconds <= 0L) return "Finishing…"
        val totalMin = (seconds + 59) / 60
        val hours = totalMin / 60
        val minutes = totalMin % 60
        return when {
            hours > 0 && minutes > 0 -> "~$hours h $minutes min"
            hours > 0 -> "~$hours h"
            else -> "~$minutes min"
        }
    }
}
