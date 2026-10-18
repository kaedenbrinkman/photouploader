package com.photopipeline.app.upload

import kotlin.math.max

/** Rolling upload speed samples for current / average / peak and ETA. */
class TransferSpeedTracker(
    private val windowMs: Long = 60_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    data class Snapshot(
        val currentBps: Long,
        val averageBps: Long,
        val peakBps: Long,
        val bytesSent: Long,
        val totalBytes: Long,
        val etaSeconds: Long?,
    )

    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var bytesSent: Long = 0
    private var totalBytes: Long = 0
    private var peakBps: Long = 0
    private var sessionStartMs: Long = 0
    private var sessionBytes: Long = 0

    fun reset(total: Long) {
        samples.clear()
        bytesSent = 0
        totalBytes = total
        peakBps = 0
        sessionStartMs = 0
        sessionBytes = 0
    }

    fun recordBytes(delta: Int) {
        if (delta <= 0) return
        val now = nowMs()
        if (sessionStartMs == 0L) sessionStartMs = now
        sessionBytes += delta
        bytesSent += delta
        samples.addLast(now to delta.toLong())
        trim(now)
        val current = currentBps(now)
        peakBps = max(peakBps, current)
    }

    fun snapshot(): Snapshot {
        val now = nowMs()
        trim(now)
        val avg = sessionAverageBps(now).takeIf { it > 0 } ?: averageBps(now)
        val cur = currentBps(now)
        val remaining = (totalBytes - bytesSent).coerceAtLeast(0)
        val eta = if (avg > 0 && remaining > 0) remaining / avg else null
        return Snapshot(cur, avg, peakBps, bytesSent, totalBytes, eta)
    }

    private fun sessionAverageBps(now: Long): Long {
        if (sessionStartMs == 0L) return 0
        val span = max(1, now - sessionStartMs)
        return sessionBytes * 1000 / span
    }

    private fun trim(now: Long) {
        while (samples.isNotEmpty() && now - samples.first().first > windowMs) {
            samples.removeFirst()
        }
    }

    private fun currentBps(now: Long): Long {
        trim(now)
        if (samples.isEmpty()) return 0
        val span = max(1, now - samples.first().first)
        val bytes = samples.sumOf { it.second }
        return bytes * 1000 / span
    }

    private fun averageBps(now: Long): Long {
        trim(now)
        if (samples.isEmpty()) return 0
        val span = max(1, now - samples.first().first)
        val bytes = samples.sumOf { it.second }
        return bytes * 1000 / span
    }
}
