package com.photopipeline.app.upload

/** S3 multipart part layout (5 MiB minimum per part except the last). */
object MultipartPlanner {
    const val S3_MIN_PART_BYTES: Long = 5L * 1024 * 1024
    const val DEFAULT_PART_BYTES: Long = 8L * 1024 * 1024

    data class PartPlan(val partNumber: Int, val offset: Long, val length: Long)

    fun planParts(fileSizeBytes: Long, partSizeBytes: Long = DEFAULT_PART_BYTES): List<PartPlan> {
        require(fileSizeBytes > 0) { "fileSizeBytes must be positive" }
        val partSize = partSizeBytes.coerceAtLeast(S3_MIN_PART_BYTES)
        if (fileSizeBytes <= partSize) {
            return listOf(PartPlan(1, 0, fileSizeBytes))
        }
        val parts = mutableListOf<PartPlan>()
        var offset = 0L
        var partNumber = 1
        while (offset < fileSizeBytes) {
            val remaining = fileSizeBytes - offset
            val len = if (remaining <= partSize) {
                remaining
            } else {
                val nextRemain = remaining - partSize
                if (nextRemain in 1 until S3_MIN_PART_BYTES) {
                    remaining - S3_MIN_PART_BYTES
                } else {
                    partSize
                }
            }
            parts.add(PartPlan(partNumber, offset, len))
            offset += len
            partNumber++
        }
        return parts
    }

    fun resumePartIndex(plans: List<PartPlan>, uploadedBytes: Long): Int {
        if (uploadedBytes <= 0) return 0
        val idx = plans.indexOfFirst { uploadedBytes <= it.offset + it.length - 1 }
        return if (idx < 0) plans.size else idx
    }

    fun completedPartsCount(plans: List<PartPlan>, uploadedBytes: Long): Int =
        plans.count { uploadedBytes >= it.offset + it.length }
}
