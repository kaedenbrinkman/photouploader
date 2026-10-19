package com.photopipeline.app.upload

import com.photopipeline.app.data.FileEntity
import kotlin.math.max

/** Session upload progress aligned with the in-app upload progress card. */
data class UploadProgressSnapshot(
    val bytesSent: Long,
    val totalBytes: Long,
    val filesDone: Int,
    val filesTotal: Int,
    val currentBps: Long,
    val averageBps: Long,
    val etaSeconds: Long?,
) {
    companion object {
        fun compute(
            files: List<FileEntity>,
            metrics: LiveUploadMetrics,
            uploading: Boolean,
        ): UploadProgressSnapshot {
            val filesDone = if (metrics.filesTotal > 0) {
                metrics.filesDone
            } else {
                files.count { it.uploadState == "UPLOADED" }
            }
            val filesTotal = if (metrics.filesTotal > 0) metrics.filesTotal else files.size
            val totalBytes = files.sumOf { it.sizeBytes }.coerceAtLeast(1)
            val bytesFromFiles = files.sumOf { it.uploadedBytes }
            val bytesSent = when {
                uploading && metrics.totalBytes > 0 -> max(metrics.bytesSent, bytesFromFiles)
                else -> bytesFromFiles
            }
            val remaining = (totalBytes - bytesSent).coerceAtLeast(0)
            val etaSeconds = UploadEtaEstimator.estimateSeconds(
                remainingBytes = remaining,
                currentBps = metrics.currentBps,
                averageBps = metrics.averageBps,
                sessionBytes = metrics.sessionBytes,
                sessionElapsedMs = metrics.sessionElapsedMs,
            )
            return UploadProgressSnapshot(
                bytesSent = bytesSent,
                totalBytes = totalBytes,
                filesDone = filesDone,
                filesTotal = filesTotal,
                currentBps = metrics.currentBps,
                averageBps = metrics.averageBps,
                etaSeconds = etaSeconds,
            )
        }
    }
}

fun formatUploadBytes(n: Long): String = when {
    n >= 1_000_000_000 -> "%.2f GB".format(n / 1_000_000_000.0)
    n >= 1_000_000 -> "%.1f MB".format(n / 1_000_000.0)
    n >= 1_000 -> "%.0f KB".format(n / 1_000.0)
    else -> "$n B"
}

fun formatUploadMbps(bps: Long): String = "%.1f Mbps".format(bps * 8.0 / 1_000_000.0)
