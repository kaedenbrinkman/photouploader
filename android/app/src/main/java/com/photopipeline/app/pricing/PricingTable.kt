package com.photopipeline.app.pricing

data class PricingTable(
    val ec2: Ec2Pricing,
    val s3: S3Pricing,
    val lambdaAndApi: LambdaApiPricing,
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
)

data class LambdaApiPricing(
    val estimatePerShootUsd: Double,
)
