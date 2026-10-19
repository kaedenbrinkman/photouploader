package com.photopipeline.app.upload

import com.photopipeline.app.api.ApiRequestException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleMultipartRecoveryTest {
    @Test
    fun http404IsStale() {
        assertTrue(StaleMultipartRecovery.isStaleMultipartError(ApiRequestException("x", 404, null)))
    }

    @Test
    fun noSuchUploadErrorCodeIsStale() {
        assertTrue(
            StaleMultipartRecovery.isStaleMultipartError(
                ApiRequestException("x", 409, "NoSuchUpload"),
            ),
        )
    }

    @Test
    fun entityTooSmallIsNotStale() {
        assertFalse(
            StaleMultipartRecovery.isStaleMultipartError(
                ApiRequestException("x", 409, "EntityTooSmall"),
            ),
        )
    }

    @Test
    fun staleMultipartFailureIncludesPartPutException() {
        assertTrue(
            StaleMultipartRecovery.isStaleMultipartFailure(
                StaleMultipartUploadException("part 404"),
            ),
        )
    }
}
