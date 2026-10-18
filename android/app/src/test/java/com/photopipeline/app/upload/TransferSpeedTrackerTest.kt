package com.photopipeline.app.upload

import org.junit.Assert.assertTrue
import org.junit.Test

class TransferSpeedTrackerTest {
    @Test
    fun etaUsesAverageSpeed() {
        var now = 0L
        val tracker = TransferSpeedTracker(windowMs = 60_000, nowMs = { now })
        tracker.reset(100_000)
        now = 1000
        tracker.recordBytes(5000)
        now = 2000
        tracker.recordBytes(5000)
        val snap = tracker.snapshot()
        assertTrue(snap.averageBps > 0)
        assertTrue(snap.etaSeconds != null && snap.etaSeconds!! > 0)
    }
}
