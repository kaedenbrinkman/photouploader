package com.photopipeline.app.upload

import com.photopipeline.app.api.ApiClient
import com.photopipeline.app.api.ApiRequestException
import kotlinx.coroutines.delay

object MultipartComplete {
    suspend fun completeWithRetry(
        api: ApiClient,
        shootId: String,
        sha256: String,
        key: String,
        uploadId: String,
        parts: List<UploadPart>,
        transferAcceleration: Boolean,
        expectedSizeBytes: Long?,
    ) {
        var attempt = 0
        while (true) {
            try {
                api.completeMultipart(shootId, sha256, key, uploadId, parts, transferAcceleration)
                return
            } catch (e: ApiRequestException) {
                val retryable = e.httpCode == 429 ||
                    e.httpCode == 500 ||
                    e.httpCode == 502 ||
                    e.httpCode == 503 ||
                    e.httpCode == 504
                if (!retryable) throw e
                attempt++
                if (attempt > 8) throw e
                delay(RetryPolicy.backoffMs(attempt))
                val head = runCatching {
                    api.headObject(shootId, key, transferAcceleration)
                }.getOrNull()
                if (head?.exists == true) {
                    if (expectedSizeBytes == null || head.contentLength == expectedSizeBytes) {
                        return
                    }
                }
            }
        }
    }
}
