package com.photopipeline.app.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UploadEtaEstimatorTest {
    @Before
    fun reset() {
        UploadEtaEstimator.reset()
    }

    @Test
    fun hidesEtaUntilWarmup() {
        assertNull(
            UploadEtaEstimator.estimateSeconds(
                remainingBytes = 10_000_000_000L,
                currentBps = 50_000_000,
                averageBps = 40_000_000,
                sessionBytes = 1_000_000,
                sessionElapsedMs = 2_000,
            ),
        )
    }

    @Test
    fun blendedSpeedUsesAverageWhenCurrentZero() {
        val eta = UploadEtaEstimator.estimateSeconds(
            remainingBytes = 10_000_000,
            currentBps = 0,
            averageBps = 500_000,
            sessionBytes = 12_000_000,
            sessionElapsedMs = 6_000,
        )!!
        assertTrue(eta in 18L..22L)
    }

    @Test
    fun formatEta_minutesAndHours() {
        assertEquals("Estimating…", UploadEtaEstimator.formatEta(null))
        assertEquals("~32 min", UploadEtaEstimator.formatEta(32 * 60))
        assertEquals("~1 h 5 min", UploadEtaEstimator.formatEta(65 * 60))
    }

    @Test
    fun blendedSpeedAndSmoothing() {
        val first = UploadEtaEstimator.estimateSeconds(
            10_000_000,
            1_000_000,
            500_000,
            sessionBytes = 12_000_000,
            sessionElapsedMs = 6_000,
        )!!
        assertTrue(first in 12L..15L)
        val second = UploadEtaEstimator.estimateSeconds(
            10_000_000,
            100_000,
            500_000,
            sessionBytes = 12_000_000,
            sessionElapsedMs = 7_000,
        )!!
        assertTrue("ETA should rise smoothly when speed drops", second > first)
    }
}
