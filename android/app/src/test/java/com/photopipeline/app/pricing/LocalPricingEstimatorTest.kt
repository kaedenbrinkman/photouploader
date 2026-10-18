package com.photopipeline.app.pricing

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalPricingEstimatorTest {
    @Test
    fun estimateScalesWithImages() {
        val json = File("src/main/assets/pricing.json").readText()
        val est = LocalPricingEstimator(json)
        val small = est.estimateShoot(10, 1_000_000_000L).estimate
        val large = est.estimateShoot(500, 30_000_000_000L).estimate
        assertTrue(large.totalShootUsd > small.totalShootUsd)
        assertTrue(large.processingMinutes > small.processingMinutes)
    }
}
