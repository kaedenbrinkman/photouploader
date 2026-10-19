package com.photopipeline.app.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Normalize MTP / EXIF timestamps to sane epoch milliseconds for grouping. */
object CaptureTimeNormalizer {
    private const val MS_THRESHOLD = 1_000_000_000_000L
    private const val MIN_YEAR = 1990
    private const val MAX_YEAR = 2100

    private val exifPatterns = listOf(
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US),
    )

    /** MTP fields may be seconds or milliseconds depending on device / API path. */
    fun normalizeEpochToMs(raw: Long): Long? {
        if (raw <= 0L) return null
        val ms = if (raw < MS_THRESHOLD) raw * 1000L else raw
        return ms.takeIf { isPlausibleEpochMs(it) }
    }

    fun isPlausibleEpochMs(epochMs: Long): Boolean {
        if (epochMs <= 0L) return false
        val year = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).year
        return year in MIN_YEAR..MAX_YEAR
    }

    /**
     * Prefer modified, then created; pick first plausible normalized value.
     */
    fun resolveMtpCaptureEpochMs(dateCreatedRaw: Long, dateModifiedRaw: Long): Long {
        val candidates = listOf(dateModifiedRaw, dateCreatedRaw)
            .mapNotNull { normalizeEpochToMs(it) }
        return candidates.firstOrNull() ?: 0L
    }

    fun bestCaptureEpochMs(vararg candidatesMs: Long?): Long {
        for (raw in candidatesMs) {
            val ms = raw ?: continue
            val normalized = if (raw < MS_THRESHOLD) normalizeEpochToMs(raw) else ms.takeIf { isPlausibleEpochMs(it) }
            if (normalized != null) return normalized
        }
        return 0L
    }

    /** EXIF datetime original / digitized from thumbnail or file bytes. */
    fun parseExifDateTimeToEpochMs(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        for (pattern in exifPatterns) {
            runCatching {
                val ldt = LocalDateTime.parse(trimmed, pattern)
                val ms = ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (isPlausibleEpochMs(ms)) return ms
            }
        }
        return null
    }
}
