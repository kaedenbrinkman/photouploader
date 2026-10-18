package com.photopipeline.app.upload

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UploadEtaEstimatorTest {
    @Before
    fun reset() {
        UploadEtaEstimator.reset()
    }

    @Test
    fun blendedSpeedAndSmoothing() {
        val first = UploadEtaEstimator.estimateSeconds(10_000_000, 1_000_000, 500_000)!!
        assertTrue(first in 12L..15L)
        val second = UploadEtaEstimator.estimateSeconds(10_000_000, 100_000, 500_000)!!
        assertNotNull(second)
        assertTrue("ETA should rise smoothly when speed drops", second > first)
        assertTrue("ETA should stay below unsmoothed current-only estimate", second < 10_000_000 / 100_000)
    }
}
