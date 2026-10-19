package com.photopipeline.app.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class FinalizeStartRecoveryTest {
    @Test
    fun socketTimeoutIsMaybeStarted() {
        assertTrue(FinalizeStartRecovery.isMaybeStartedFailure(SocketTimeoutException("timeout")))
    }

    @Test
    fun finalizeInProgressIsMaybeStarted() {
        assertTrue(
            FinalizeStartRecovery.isMaybeStartedFailure(
                ApiRequestException("x", 409, "finalize_in_progress"),
            ),
        )
    }

    @Test
    fun launchingPhaseCountsAsStarted() {
        val status = ShootStatusResponse(
            shootId = "s1",
            state = "READY_TO_PROCESS",
            phase = "LAUNCHING",
            updatedAt = 1L,
            albumId = null,
            progress = null,
            actualCompute = null,
            files = null,
            results = null,
        )
        assertTrue(FinalizeStartRecovery.processingAppearsStarted(status))
    }

    @Test
    fun entityTooSmallIsNotMaybeStarted() {
        assertFalse(
            FinalizeStartRecovery.isMaybeStartedFailure(
                ApiRequestException("x", 409, "EntityTooSmall"),
            ),
        )
    }
}
