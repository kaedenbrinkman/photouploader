package com.photopipeline.app.upload

/**
 * Smoothed ETA from remaining bytes and a 50/50 blend of current and session-average speed.
 */
object UploadEtaEstimator {
    private var smoothedSeconds: Double? = null

    fun reset() {
        smoothedSeconds = null
    }

    fun estimateSeconds(remainingBytes: Long, currentBps: Long, averageBps: Long): Long? {
        if (remainingBytes <= 0) {
            smoothedSeconds = 0.0
            return 0L
        }
        val cur = currentBps.coerceAtLeast(0)
        val avg = averageBps.coerceAtLeast(0)
        val effective = (0.5 * cur + 0.5 * avg).toLong().coerceAtLeast(1L)
        val raw = remainingBytes.toDouble() / effective.toDouble()
        smoothedSeconds = when (val prev = smoothedSeconds) {
            null -> raw
            else -> prev * 0.85 + raw * 0.15
        }
        return smoothedSeconds?.toLong()?.coerceAtLeast(0L)
    }
}
