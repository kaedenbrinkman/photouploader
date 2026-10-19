package com.photopipeline.app.pricing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class RunningComputeEstimateTest {
    private val ec2: Ec2Pricing by lazy {
        val json = File("src/main/assets/pricing.json").readText()
        LocalPricingEstimator(json).ec2
    }

    @Test
    fun t3aMediumSpotCostAfter14Minutes() {
        val started = 1_000_000L
        val now = started + 14 * 60 * 1000L
        val cost = RunningComputeEstimate.runningActualCompute(
            workerStartedAtMs = started,
            workerFinishedAtMs = null,
            instanceType = "t3a.medium",
            spot = true,
            nowMs = now,
            ec2 = ec2,
        )!!
        assertEquals(840, cost.seconds)
        assertEquals(0.0028, cost.usd, 0.0001)
    }

    @Test
    fun c7i4xlargeOnDemandOneHour() {
        val started = 0L
        val usd = RunningComputeEstimate.actualComputeCost(3600.0, "c7i.4xlarge", spot = false, ec2 = ec2)
        assertEquals(0.714, usd, 0.0001)
    }

    @Test
    fun emptyWithoutInstanceType() {
        assertNull(
            RunningComputeEstimate.runningActualCompute(
                workerStartedAtMs = 100L,
                workerFinishedAtMs = null,
                instanceType = null,
                spot = true,
                nowMs = 200L,
                ec2 = ec2,
            ),
        )
    }
}
