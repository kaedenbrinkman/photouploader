package com.photopipeline.app.upload

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LiveUploadMetrics(
    val shootId: String = "",
    val currentBps: Long = 0,
    val averageBps: Long = 0,
    val peakBps: Long = 0,
    val bytesSent: Long = 0,
    val totalBytes: Long = 0,
    val etaSeconds: Long? = null,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val partsDone: Int = 0,
    val partsTotal: Int = 0,
    val retries: Int = 0,
    val lastError: String? = null,
    val pausedReason: String? = null,
    val network: NetworkSnapshot? = null,
    val speedHistoryBps: List<Long> = emptyList(),
)

object UploadMetricsStore {
    private const val HISTORY = 24
    private val _metrics = MutableStateFlow(LiveUploadMetrics())
    val metrics: StateFlow<LiveUploadMetrics> = _metrics.asStateFlow()

    private val speedHistory = ArrayDeque<Long>()

    fun update(transform: (LiveUploadMetrics) -> LiveUploadMetrics) {
        _metrics.value = transform(_metrics.value)
    }

    fun pushSpeedSample(bps: Long) {
        if (bps <= 0) return
        speedHistory.addLast(bps)
        while (speedHistory.size > HISTORY) speedHistory.removeFirst()
        _metrics.value = _metrics.value.copy(speedHistoryBps = speedHistory.toList())
    }

    fun clear() {
        speedHistory.clear()
        _metrics.value = LiveUploadMetrics()
    }
}
