package com.photopipeline.app.mtp

/** Pure formatting helpers for MTP camera UI (unit-tested). */
object CameraMtpFormat {
    fun cameraDisplayTitle(manufacturer: String?, model: String?): String {
        val m = model?.trim().orEmpty()
        val man = manufacturer?.trim().orEmpty()
        if (m.isBlank()) return man.ifBlank { "Camera" }
        if (man.isBlank()) return m
        if (m.contains(man, ignoreCase = true)) return m
        return "$man $m"
    }

    fun maskSerialNumber(serial: String?): String? {
        val s = serial?.trim().orEmpty()
        if (s.length < 4) return null
        val last4 = s.takeLast(4)
        return "S/N •••$last4"
    }

    fun formatStorageGb(bytes: Long): String {
        if (bytes <= 0L) return "0 GB"
        val gb = bytes.toDouble() / 1_000_000_000.0
        return if (gb >= 100) {
            "%.0f GB".format(gb)
        } else if (gb >= 10) {
            "%.1f GB".format(gb)
        } else {
            "%.1f GB".format(gb)
        }
    }

    fun formatStorageFreeOfTotal(freeBytes: Long, maxBytes: Long): String {
        return "${formatStorageGb(freeBytes)} free of ${formatStorageGb(maxBytes)}"
    }

    fun storageUsedProgress(freeBytes: Long, maxBytes: Long): Float {
        if (maxBytes <= 0L) return 0f
        val used = (maxBytes - freeBytes).coerceIn(0L, maxBytes)
        return (used.toFloat() / maxBytes.toFloat()).coerceIn(0f, 1f)
    }

    fun isStorageLow(freeBytes: Long, maxBytes: Long): Boolean {
        if (maxBytes <= 0L) return false
        return freeBytes * 100 / maxBytes < 10
    }
}
