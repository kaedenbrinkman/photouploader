package com.photopipeline.app.api

data class CostEstimateDto(
    val imageCount: Int,
    val totalBytesRaw: Long,
    val instanceType: String,
    val spot: Boolean,
    val processingMinutes: Double,
    val computeUsd: Double,
    val s3StandardMonthUsd: Double,
    val s3DeepArchiveMonthUsd: Double,
    val requestsUsd: Double,
    val transferAccelerationUsd: Double = 0.0,
    val totalShootUsd: Double,
)

data class EstimateResponse(val estimate: CostEstimateDto, val notes: List<String>)

data class ShootStatusResponse(
    val shootId: String,
    val state: String,
    val phase: String?,
    val albumId: String?,
    val progress: ProgressDto?,
    val actualCompute: ActualComputeDto?,
    val files: List<FileStatusDto>?,
    val results: Map<String, Any>?,
)

data class ProgressDto(
    val totalFiles: Int,
    val renderedCount: Int,
    val publishedCount: Int,
    val uploadCompleteCount: Int,
)

data class ActualComputeDto(val seconds: Int?, val usd: Double?)

data class FileStatusDto(
    val sha256: String?,
    val state: String?,
    val error: String?,
    val originalName: String?,
)
