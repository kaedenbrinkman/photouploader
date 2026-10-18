package com.photopipeline.app.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultipartPlannerTest {
    @Test
    fun smallFileSinglePart() {
        val plans = MultipartPlanner.planParts(1024, MultipartPlanner.DEFAULT_PART_BYTES)
        assertEquals(1, plans.size)
        assertEquals(1024, plans[0].length)
    }

    @Test
    fun partsCoverFullFile() {
        val size = 20L * 1024 * 1024
        val plans = MultipartPlanner.planParts(size, 8L * 1024 * 1024)
        assertTrue(plans.size >= 2)
        assertEquals(size, plans.sumOf { it.length })
    }

    @Test
    fun resumeIndexFromUploadedBytes() {
        val plans = MultipartPlanner.planParts(20L * 1024 * 1024, 8L * 1024 * 1024)
        val idx = MultipartPlanner.resumePartIndex(plans, 8L * 1024 * 1024)
        assertEquals(1, idx)
    }
}
