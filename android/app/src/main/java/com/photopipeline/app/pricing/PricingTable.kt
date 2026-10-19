package com.photopipeline.app.pricing

data class PricingTable(
    val ec2: Ec2Pricing,
    val s3: S3Pricing,
    val lambdaAndApi: LambdaApiPricing,
    val ebs: EbsPricing? = null,
)

data class EbsPricing(
    val gp3UsdPerGbMonth: Double = 0.08,
)

data class Ec2Pricing(
    val instanceTypeOrder: List<String>,
    val bootOverheadMinutes: Int,
    val parallelism: Map<String, Int>,
    val secondsPerImageAtReferenceParallelism: Double,
    val preferSpot: Boolean = true,
    val spotUsdPerHour: Map<String, Double>,
    val onDemandUsdPerHour: Map<String, Double>,
)

data class S3Pricing(
    val standardUsdPerGbMonth: Double,
    val deepArchiveUsdPerGbMonth: Double,
    val putPer1000: Double,
    val transferAccelerationUsdPerGb: Double = 0.04,
)

data class LambdaApiPricing(
    val estimatePerShootUsd: Double,
)
