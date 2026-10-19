package com.photopipeline.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadUiStateTest {
    @Test
    fun uploadWorkerActive_whenUploadingOrStarting() {
        assertTrue(isUploadWorkerActive(UploadStartState.Uploading))
        assertTrue(isUploadWorkerActive(UploadStartState.Starting))
        assertFalse(isUploadWorkerActive(UploadStartState.Idle))
    }

    @Test
    fun capProgressCounts_limitsRenderedAndPublishedToTotal() {
        val (rendered, total, published) = capProgressCounts(26, 13, 5)
        assertEquals(13, rendered)
        assertEquals(13, total)
        assertEquals(5, published)
    }
}
