package com.photopipeline.app.mtp

/** Pure formatting helpers for MTP camera UI (unit-tested). */
object CameraMtpFormat {
    fun cameraDisplayTitle(manufacturer: String?, model: String?): String {
        val m = model?.trim().orEmpty()
        val manNorm = normalizeManufacturerName(manufacturer?.trim().orEmpty())
        if (m.isBlank()) return manNorm.ifBlank { "Camera" }
        if (manNorm.isBlank()) return m

        val brand = manufacturerBrandWord(manNorm)
        if (brand.isNotEmpty() && modelStartsWithBrand(m, brand)) {
            return m
        }
        if (m.contains(manNorm, ignoreCase = true)) {
            return m
        }
        return "$manNorm $m"
    }

    /** e.g. "Canon.Inc" → "Canon", "Sony Corporation" → "Sony" */
    internal fun normalizeManufacturerName(manufacturer: String): String {
        if (manufacturer.isBlank()) return ""
        var s = manufacturer.replace('.', ' ').replace(Regex("\\s+"), " ").trim()
        val suffix = Regex(
            "\\s+(Inc\\.?|Incorporated|LLC|Ltd\\.?|Limited|Corp\\.?|Corporation|Co\\.?|Company)$",
            RegexOption.IGNORE_CASE,
        )
        while (true) {
            val next = s.replace(suffix, "").trim()
            if (next == s) break
            s = next
        }
        return s.trim()
    }

    internal fun manufacturerBrandWord(normalizedManufacturer: String): String =
        normalizedManufacturer.split(Regex("\\s+")).firstOrNull().orEmpty()

    private fun modelStartsWithBrand(model: String, brand: String): Boolean {
        if (!model.regionMatches(0, brand, 0, brand.length, ignoreCase = true)) return false
        if (model.length == brand.length) return true
        return !model[brand.length].isLetterOrDigit()
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
