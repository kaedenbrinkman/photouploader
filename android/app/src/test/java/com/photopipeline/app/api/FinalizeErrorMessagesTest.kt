package com.photopipeline.app.api

import org.junit.Assert.assertEquals
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
    fun prefersServerMessageOverBareTimeout() {
        val msg = FinalizeErrorMessages.mapServerWorkerMessage("timeout")
        assertEquals(null, msg)
    }
}
