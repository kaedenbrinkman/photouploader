package com.photopipeline.app.upload

import kotlin.math.max
import kotlin.math.min

/** Rolling upload speed samples for current / average / peak and ETA. */
class TransferSpeedTracker(
    private val windowMs: Long = 5_000L,
    private val minSampleDtMs: Long = 500L,
    private val maxPlausibleBps: Long = 1_000_000_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    data class Snapshot(
        val currentBps: Long,
        val averageBps: Long,
        val peakBps: Long,
        val bytesSent: Long,
        val totalBytes: Long,
        val sessionBytes: Long,
        val sessionElapsedMs: Long,
        val etaSeconds: Long?,
    )

    private data class Sample(val atMs: Long, val bytes: Long)

    private val samples = ArrayDeque<Sample>()
    private var bytesSent: Long = 0
    private var totalBytes: Long = 0
    private var peakBps: Long = 0
    private var sessionStartMs: Long = 0
    private var sessionBytes: Long = 0
    private var lastSampleAtMs: Long = 0

    fun reset(total: Long) {
        samples.clear()
        bytesSent = 0
        totalBytes = total
        peakBps = 0
        sessionStartMs = 0
        sessionBytes = 0
        lastSampleAtMs = 0
    }

    fun recordBytes(delta: Int) {
        if (delta <= 0) return
        val now = nowMs()
        if (sessionStartMs == 0L) sessionStartMs = now
        sessionBytes += delta
        bytesSent += delta
        if (lastSampleAtMs == 0L || now - lastSampleAtMs >= minSampleDtMs) {
            samples.addLast(Sample(now, delta.toLong()))
            lastSampleAtMs = now
            trim(now)
            val current = windowBps(now)
            peakBps = max(peakBps, min(current, maxPlausibleBps))
        }
    }

    fun snapshot(): Snapshot {
        val now = nowMs()
        trim(now)
        val cur = windowBps(now)
        val avg = sessionAverageBps(now)
        val remaining = (totalBytes - bytesSent).coerceAtLeast(0)
        val elapsed = if (sessionStartMs > 0L) now - sessionStartMs else 0L
        val eta = UploadEtaEstimator.estimateSeconds(
            remainingBytes = remaining,
            currentBps = cur,
            averageBps = avg,
            sessionBytes = sessionBytes,
            sessionElapsedMs = elapsed,
        )
        return Snapshot(
            cur,
            avg,
            peakBps,
            bytesSent,
            totalBytes,
            sessionBytes,
            elapsed,
            eta,
        )
    }

    private fun sessionAverageBps(now: Long): Long {
        if (sessionStartMs == 0L || sessionBytes <= 0L) return 0
        val span = max(minSampleDtMs, now - sessionStartMs)
        return min(sessionBytes * 1000 / span, maxPlausibleBps)
    }

    private fun trim(now: Long) {
        while (samples.isNotEmpty() && now - samples.first().atMs > windowMs) {
            samples.removeFirst()
        }
    }

    private fun windowBps(now: Long): Long {
        trim(now)
        if (samples.isEmpty()) return 0
        val oldest = samples.first().atMs
        val span = max(minSampleDtMs, now - oldest)
        val bytes = samples.sumOf { it.bytes }
        if (bytes <= 0) return 0
        return min(bytes * 1000 / span, maxPlausibleBps)
    }
}
