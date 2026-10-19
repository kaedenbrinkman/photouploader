package com.photopipeline.app.upload

import com.photopipeline.app.api.FileStatusDto
import com.photopipeline.app.api.ProgressDto
import com.photopipeline.app.data.FileEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadServerReconcileTest {
    @Test
    fun marksLocalWhenServerShaMatchesUploadRef() {
        val uploadRef = "abc123"
        val file = localFile(sha256 = "", uploadRefAsContent = uploadRef)
        val server = listOf(FileStatusDto(uploadRef, "UPLOADED", null, "IMG.cr3", null))
        assertTrue(
            UploadServerReconcile.shouldMarkLocalUploaded(
                file,
                UploadServerReconcile.serverUploadedShaKeys(server),
                server,
            ),
        )
    }

    @Test
    fun serverReportsAllUploaded_whenProgressMatches() {
        val server = (1..374).map { i ->
            FileStatusDto("sha$i", "UPLOADED", null, "f$i.cr3", 1000L)
        }
        val progress = ProgressDto(
            totalFiles = 374,
            renderedCount = 0,
            publishedCount = 0,
            uploadCompleteCount = 374,
        )
        assertTrue(UploadServerReconcile.serverReportsAllUploaded(374, server, progress))
    }

    @Test
    fun doesNotMarkWhenServerStillUploading() {
        val file = localFile(sha256 = "deadbeef", uploadRefAsContent = "deadbeef")
        val server = listOf(FileStatusDto("deadbeef", "UPLOADING", null, "a.cr3", 100L))
        assertFalse(
            UploadServerReconcile.shouldMarkLocalUploaded(
                file,
                UploadServerReconcile.serverUploadedShaKeys(server),
                server,
            ),
        )
    }

    private fun localFile(sha256: String, uploadRefAsContent: String): FileEntity {
        // storageId/handle/size/capture chosen so metadataUploadSha256 == uploadRefAsContent is hard;
        // use sha256 field for lookup instead.
        return FileEntity(
            identityKey = "k1",
            shootId = "s1",
            sha256 = sha256.ifBlank { uploadRefAsContent },
            originalName = "IMG.cr3",
            sizeBytes = 100,
            storageId = 1L,
            objectHandle = 2L,
            captureTimeEpochMs = 3L,
            localPath = null,
            uploadSource = "CAMERA_STREAM",
            uploadState = "UPLOADING",
            uploadedBytes = 50,
            uploadSpeedBps = 0,
            retryCount = 0,
            lastError = null,
            s3UploadId = "u",
            s3Key = "k",
        )
    }
}
