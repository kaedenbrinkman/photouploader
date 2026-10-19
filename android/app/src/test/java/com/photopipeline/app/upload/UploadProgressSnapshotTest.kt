package com.photopipeline.app.upload

import com.photopipeline.app.data.FileEntity
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class UploadProgressSnapshotTest {
    @Before
    fun resetEta() {
        UploadEtaEstimator.reset()
    }

    @Test
    fun compute_usesFileBytesAndMetricsMaxWhileUploading() {
        val files = listOf(
            file(uploadedBytes = 100, sizeBytes = 200, state = "UPLOADING"),
            file(uploadedBytes = 50, sizeBytes = 100, state = "UPLOADED"),
        )
        val metrics = LiveUploadMetrics(
            filesTotal = 2,
            filesDone = 1,
            bytesSent = 150,
            totalBytes = 200,
            averageBps = 2_000_000,
            sessionBytes = 20_000_000,
            sessionElapsedMs = 10_000,
        )
        val snap = UploadProgressSnapshot.compute(files, metrics, uploading = true)
        assertEquals(150, snap.bytesSent)
        assertEquals(300, snap.totalBytes)
        assertEquals(1, snap.filesDone)
        assertEquals(2, snap.filesTotal)
    }

    private fun file(
        uploadedBytes: Long,
        sizeBytes: Long,
        state: String,
    ) = FileEntity(
        identityKey = "k$uploadedBytes",
        shootId = "s1",
        originalName = "a.jpg",
        sizeBytes = sizeBytes,
        uploadedBytes = uploadedBytes,
        uploadState = state,
        storageId = 0L,
        objectHandle = 0L,
        captureTimeEpochMs = 0L,
        sha256 = "",
        localPath = null,
        uploadSource = "CAMERA_STREAM",
        s3Key = null,
        s3UploadId = null,
        uploadPartsJson = "[]",
        uploadSpeedBps = 0,
        retryCount = 0,
        lastError = null,
    )
}
