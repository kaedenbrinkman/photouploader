package com.photopipeline.app.pricing

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalPricingEstimatorTest {
    @Test
    fun estimateScalesWithImages() {
        val json = File("src/main/assets/pricing.json").readText()
        val est = LocalPricingEstimator(json)
        val small = est.estimateShoot(10, 0, 1_000_000_000L).estimate
        val large = est.estimateShoot(500, 0, 30_000_000_000L).estimate
        assertTrue(large.totalShootUsd > small.totalShootUsd)
        assertTrue(large.processingMinutes > small.processingMinutes)
    }

    @Test
    fun transferAccelerationAddsSurcharge() {
        val json = File("src/main/assets/pricing.json").readText()
        val est = LocalPricingEstimator(json)
        val bytes = 10L * 1024 * 1024 * 1024
        val base = est.estimateShoot(100, 0, bytes).estimate
        val accel = est.estimateShoot(100, 0, bytes, transferAcceleration = true).estimate
        org.junit.Assert.assertEquals(0.4, accel.transferAccelerationUsd, 0.0001)
        org.junit.Assert.assertEquals(base.totalShootUsd + 0.4, accel.totalShootUsd, 0.0001)
    }
}
