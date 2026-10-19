package com.photopipeline.app.ui

import com.photopipeline.app.data.FileEntity
import com.photopipeline.app.data.ShootEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessingPhaseUiTest {
    @Test
    fun resolveProcessingPipelineStep_mapsBootstrapToSettingUp() {
        assertEquals(
            ProcessingPipelineStep.SettingUp,
            resolveProcessingPipelineStep("PROCESSING", "BOOTSTRAPPING", false),
        )
    }

    @Test
    fun resolveProcessingPipelineStep_mapsPublish() {
        assertEquals(
            ProcessingPipelineStep.Publishing,
            resolveProcessingPipelineStep("PROCESSING", "PUBLISH", false),
        )
    }

    @Test
    fun computeFlowStep_processingWhenAllUploaded() {
        val shoot = ShootEntity(
            shootId = "s1",
            albumTitle = "t",
            state = "LOCAL",
            phase = "MANIFEST",
            createdAt = 0,
            imageCount = 1,
            totalBytes = 1,
            estimateJson = null,
            backendState = null,
            albumId = null,
            actualComputeJson = null,
        )
        val files = listOf(
            sampleFile(uploadState = "UPLOADED"),
        )
        assertEquals(ShootFlowStep.Processing, computeFlowStep(shoot, files))
    }

    private fun sampleFile(uploadState: String) = FileEntity(
        identityKey = "k",
        shootId = "s1",
        sha256 = "abc",
        originalName = "IMG.jpg",
        sizeBytes = 100,
        storageId = 1,
        objectHandle = 2,
        captureTimeEpochMs = 3,
        localPath = null,
        uploadState = uploadState,
        uploadedBytes = 0,
        uploadSpeedBps = 0,
        retryCount = 0,
        lastError = null,
        s3UploadId = null,
        s3Key = null,
    )
}
