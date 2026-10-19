package com.photopipeline.app.upload

import org.junit.Assert.assertTrue
import org.junit.Test

class TransferSpeedTrackerTest {
    @Test
    fun ignoresTinyDeltasForPeak() {
        var now = 0L
        val tracker = TransferSpeedTracker(nowMs = { now })
        tracker.reset(10_000_000)
        now = 1_000
        tracker.recordBytes(5_000_000)
        now = 1_100
        tracker.recordBytes(5_000_000)
        val snap = tracker.snapshot()
        assertTrue("peak should stay below absurd Mbps", snap.peakBps < 500_000_000L)
    }
}
