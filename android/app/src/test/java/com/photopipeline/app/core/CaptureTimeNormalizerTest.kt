package com.photopipeline.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CaptureTimeNormalizerTest {
    @Test
    fun normalizeSeconds_oct182026() {
        val noon = LocalDate.of(2026, 10, 18).atStartOfDay(ZoneId.systemDefault()).plusHours(14)
        val epochSec = noon.toInstant().epochSecond
        val ms = CaptureTimeNormalizer.normalizeEpochToMs(epochSec)!!
        assertEquals(2026, LocalDate.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault()).year)
        assertEquals(10, LocalDate.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault()).monthValue)
        assertEquals(18, LocalDate.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault()).dayOfMonth)
    }

    @Test
    fun normalizeAlreadyMs_doesNotMultiplyAgain() {
        val noon = LocalDate.of(2026, 10, 18).atStartOfDay(ZoneId.systemDefault()).plusHours(14)
        val epochMs = noon.toInstant().toEpochMilli()
        assertEquals(epochMs, CaptureTimeNormalizer.normalizeEpochToMs(epochMs))
    }

    @Test
    fun rejectsAbsurdYearFromDoubleScale() {
        val noon = LocalDate.of(2026, 10, 18).atStartOfDay(ZoneId.systemDefault()).plusHours(14)
        val epochMs = noon.toInstant().toEpochMilli()
        val wronglyScaled = epochMs * 1000L
        assertTrue(!CaptureTimeNormalizer.isPlausibleEpochMs(wronglyScaled))
    }

    @Test
    fun resolveMtp_prefersModifiedWhenBothValid() {
        val day = LocalDate.of(2026, 10, 18).atStartOfDay(ZoneId.systemDefault()).plusHours(10)
        val createdSec = day.minusDays(1).toInstant().epochSecond
        val modifiedSec = day.toInstant().epochSecond
        val resolved = CaptureTimeNormalizer.resolveMtpCaptureEpochMs(createdSec, modifiedSec)
        assertEquals(day.toInstant().toEpochMilli(), resolved)
    }

    @Test
    fun parseExifDateTime() {
        val ms = CaptureTimeNormalizer.parseExifDateTimeToEpochMs("2026:10:18 15:30:00")!!
        assertEquals(2026, LocalDate.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault()).year)
    }
}
