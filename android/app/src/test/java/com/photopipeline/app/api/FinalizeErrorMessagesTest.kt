package com.photopipeline.app.api

import org.junit.Assert.assertTrue
import org.junit.Test

class FinalizeErrorMessagesTest {
    @Test
    fun mapsEnospcFromWorkerLastError() {
        val msg = FinalizeErrorMessages.friendly(
            null,
            "[Errno 28] No space left on device: download_photo_raws",
        )
        assertTrue(msg.contains("disk space", ignoreCase = true))
    }

    @Test
    fun maps409MissingCount() {
        val msg = FinalizeErrorMessages.friendly(
            ec2ErrorCode = "upload_incomplete",
            detail = null,
            httpCode = 409,
            responseState = "UPLOADING",
            missingUploadCount = 373,
        )
        assertTrue(msg.contains("373"))
        assertTrue(msg.contains("re-upload", ignoreCase = true))
    }

    @Test
    fun maps409ProcessingState() {
        val msg = FinalizeErrorMessages.friendly(
            ec2ErrorCode = null,
            detail = null,
            httpCode = 409,
            responseState = "PROCESSING",
            missingUploadCount = null,
        )
        assertTrue(msg.contains("already processing", ignoreCase = true))
    }
}
