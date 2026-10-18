package com.photopipeline.app.mtp

data class MtpObjectInfo(
    val storageId: Long,
    val handle: Long,
    val filename: String,
    val sizeBytes: Long,
    val captureTimeEpochMs: Long,
)

enum class MtpConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}
