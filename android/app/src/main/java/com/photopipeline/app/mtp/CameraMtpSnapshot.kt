package com.photopipeline.app.mtp

data class CameraStorageSnapshot(
    val description: String,
    val maxCapacityBytes: Long,
    val freeSpaceBytes: Long,
) {
    val displayLabel: String
        get() = description.ifBlank { "Storage" }

    val freeOfTotalText: String
        get() = CameraMtpFormat.formatStorageFreeOfTotal(freeSpaceBytes, maxCapacityBytes)

    val usedProgress: Float
        get() = CameraMtpFormat.storageUsedProgress(freeSpaceBytes, maxCapacityBytes)

    val isLowSpace: Boolean
        get() = CameraMtpFormat.isStorageLow(freeSpaceBytes, maxCapacityBytes)
}

data class CameraMtpSnapshot(
    val title: String,
    val firmwareVersion: String,
    val maskedSerial: String?,
    val storageVolumes: List<CameraStorageSnapshot>,
    val batteryPercent: Int?,
)
