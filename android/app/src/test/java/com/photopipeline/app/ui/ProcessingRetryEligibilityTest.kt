package com.photopipeline.app.ui

import com.photopipeline.app.data.ShootEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingRetryEligibilityTest {
    private fun progressJson(totalFiles: Int, uploadCompleteCount: Int): String =
        "{\"totalFiles\":$totalFiles,\"uploadCompleteCount\":$uploadCompleteCount,\"photoCount\":$totalFiles}"

    @Test
    fun serverUploadsComplete_requiresFullUploadCount() {
        assertTrue(
            serverUploadsComplete(
                ProcessingProgress(0, 10, 0, 0, 0, totalFiles = 10, uploadCompleteCount = 10),
            ),
        )
        assertFalse(
            serverUploadsComplete(
                ProcessingProgress(0, 10, 0, 0, 0, totalFiles = 10, uploadCompleteCount = 3),
            ),
        )
    }

    @Test
    fun retryWhenServerFailedWithoutLocalUploadComplete() {
        val shoot = sampleShoot(state = "FAILED")
        assertTrue(
            shouldOfferProcessingRetry(
                shoot = shoot,
                progressJson = progressJson(10, 10),
                serverRegistered = true,
                finalizeState = FinalizeState.Idle,
                finalizeError = null,
                serverLastError = "Worker timed out",
                localAllUploaded = false,
                staleFinalizeOnly = false,
                uploadInProgress = false,
            ),
        )
    }

    @Test
    fun noRetryWhenServerUploadsIncomplete() {
        val shoot = sampleShoot(state = "FAILED")
        assertFalse(
            shouldOfferProcessingRetry(
                shoot = shoot,
                progressJson = progressJson(10, 3),
                serverRegistered = true,
                finalizeState = FinalizeState.Idle,
                finalizeError = null,
                serverLastError = "err",
                localAllUploaded = false,
                staleFinalizeOnly = false,
                uploadInProgress = false,
            ),
        )
    }

    private fun sampleShoot(state: String) = ShootEntity(
        shootId = "290f9d10",
        albumTitle = "t",
        state = state,
        phase = "GP_FAILED",
        createdAt = 0,
        imageCount = 10,
        totalBytes = 1,
        estimateJson = null,
        backendState = state,
        albumId = null,
        actualComputeJson = null,
    )
}
