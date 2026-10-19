package com.photopipeline.app.pricing

import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Mirrors [infra/lambda/status-progress.ts] [runningActualCompute] and [infra/lambda/pricing.ts] [actualComputeCost].
 */
object RunningComputeEstimate {

    data class RunningCost(val seconds: Int, val usd: Double)

    fun actualComputeCost(computeSeconds: Double, instanceType: String, spot: Boolean, ec2: Ec2Pricing): Double {
        val seconds = if (computeSeconds.isFinite() && computeSeconds > 0) computeSeconds else 0.0
        val rateKey = if (spot) ec2.spotUsdPerHour else ec2.onDemandUsdPerHour
        val fallback = ec2.onDemandUsdPerHour["c7i.2xlarge"] ?: 0.34
        val hourlyRaw = rateKey[instanceType] ?: fallback
        val hourly = if (hourlyRaw.isFinite() && hourlyRaw > 0) hourlyRaw else fallback
        return round4(hourly * (seconds / 3600.0))
    }

    fun runningActualCompute(
        workerStartedAtMs: Long?,
        workerFinishedAtMs: Long?,
        instanceType: String?,
        spot: Boolean?,
        nowMs: Long,
        ec2: Ec2Pricing,
    ): RunningCost? {
        if (workerStartedAtMs == null || workerStartedAtMs <= 0L || instanceType.isNullOrBlank()) return null
        val endMs = workerFinishedAtMs?.takeIf { it > 0L } ?: nowMs
        val seconds = max(0.0, (endMs - workerStartedAtMs) / 1000.0)
        val usd = actualComputeCost(seconds, instanceType, spot ?: false, ec2)
        return RunningCost(seconds = seconds.roundToInt(), usd = usd)
    }

    private fun round4(n: Double): Double = round(n * 10_000.0) / 10_000.0
}
