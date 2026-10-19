package com.photopipeline.app.pricing

import com.photopipeline.app.api.CostEstimateDto
import com.photopipeline.app.api.EstimateResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlin.math.round

/**
 * Mirrors [config/pricing.json] logic in infra/lambda/pricing.ts and lib/photopipeline/pricing.py.
 */
class LocalPricingEstimator private constructor(private val table: PricingTable) {

    constructor(pricingJsonString: String) : this(parse(pricingJsonString))

    fun estimateShoot(
        photoCount: Int,
        videoCount: Int,
        totalBytesRaw: Long,
        transferAcceleration: Boolean = false,
    ): EstimateResponse {
        require(photoCount > 0 || videoCount > 0) { "photoCount or videoCount must be positive" }
        val imageCount = photoCount + videoCount
        val ec2 = table.ec2
        val s3 = table.s3
        var instanceType = ec2.instanceTypeOrder.first()
        val spot = ec2.preferSpot
        if (imageCount < 50 && ec2.instanceTypeOrder.size > 1) {
            instanceType = ec2.instanceTypeOrder[1]
        }
        val par = ec2.parallelism[instanceType] ?: 4
        val processingSeconds = if (photoCount > 0) {
            ec2.bootOverheadMinutes * 60.0 +
                (photoCount * ec2.secondsPerImageAtReferenceParallelism) / par
        } else {
            ec2.bootOverheadMinutes * 60.0 * 0.25
        }
        val hourly = (if (spot) ec2.spotUsdPerHour else ec2.onDemandUsdPerHour)[instanceType]
            ?: error("missing hourly rate for $instanceType")
        val computeUsd = hourly * (processingSeconds / 3600.0)
        val processingMinutes = round1(processingSeconds / 60.0)
        val ebsRootVolumeGiB = 30
        val gp3Month = table.ebs?.gp3UsdPerGbMonth ?: 0.08
        val ebsShootUsd = round4(ebsRootVolumeGiB * gp3Month * (processingMinutes / 60.0) / 730.0)
        val gb = totalBytesRaw / (1024.0 * 1024.0 * 1024.0)
        val s3StandardMonthUsd = gb * s3.standardUsdPerGbMonth
        val s3DeepArchiveMonthUsd = gb * s3.deepArchiveUsdPerGbMonth
        val puts = imageCount * 3
        val requestsUsd = (puts / 1000.0) * s3.putPer1000 + table.lambdaAndApi.estimatePerShootUsd
        val transferAccelerationUsd = if (transferAcceleration) {
            round4(gb * s3.transferAccelerationUsdPerGb)
        } else {
            0.0
        }
        val dto = CostEstimateDto(
            imageCount = imageCount,
            totalBytesRaw = totalBytesRaw,
            instanceType = instanceType,
            spot = spot,
            processingMinutes = processingMinutes,
            computeUsd = round4(computeUsd),
            ebsRootVolumeGiB = ebsRootVolumeGiB,
            ebsShootUsd = ebsShootUsd,
            s3StandardMonthUsd = round4(s3StandardMonthUsd),
            s3DeepArchiveMonthUsd = round4(s3DeepArchiveMonthUsd),
            requestsUsd = round4(requestsUsd),
            transferAccelerationUsd = transferAccelerationUsd,
            totalShootUsd = round4(
                computeUsd + ebsShootUsd + s3StandardMonthUsd + requestsUsd + transferAccelerationUsd,
            ),
        )
        val notes = mutableListOf("Computed on device from bundled pricing.json")
        if (transferAcceleration) {
            notes += "Includes S3 Transfer Acceleration surcharge (~$${s3.transferAccelerationUsdPerGb}/GB uploaded)."
        }
        return EstimateResponse(estimate = dto, notes = notes)
    }

    private fun round1(n: Double): Double = round(n * 10.0) / 10.0

    private fun round4(n: Double): Double = round(n * 10_000.0) / 10_000.0

    companion object {
        private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        private val adapter = moshi.adapter(PricingTable::class.java)

        fun parse(json: String): PricingTable =
            adapter.fromJson(json) ?: error("Failed to parse pricing.json")
    }
}
