package com.photopipeline.app.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultipartPlannerTest {
    private val eightMiB = 8L * 1024 * 1024
    private val fiveMiB = MultipartPlanner.S3_MIN_PART_BYTES

    @Test
    fun smallFileSinglePart() {
        val plans = MultipartPlanner.planParts(1024, MultipartPlanner.DEFAULT_PART_BYTES)
        assertEquals(1, plans.size)
        assertEquals(1024, plans[0].length)
        assertTrue(MultipartPlanner.isValidPartPlan(plans))
    }

    @Test
    fun partsCoverFullFile() {
        val size = 20L * 1024 * 1024
        val plans = MultipartPlanner.planParts(size, eightMiB)
        assertTrue(plans.size >= 2)
        assertEquals(size, plans.sumOf { it.length })
        assertAllNonLastPartsAtLeastFiveMiB(plans)
    }

    @Test
    fun img0307Size_fixedPlanner() {
        val size = 34_829_970L
        val plans = MultipartPlanner.planParts(size, eightMiB)
        assertEquals(5, plans.size)
        assertEquals(eightMiB, plans[0].length)
        assertEquals(eightMiB, plans[1].length)
        assertEquals(eightMiB, plans[2].length)
        assertEquals(eightMiB, plans[3].length)
        assertEquals(size - 4 * eightMiB, plans[4].length)
        assertEquals(size, plans.sumOf { it.length })
        assertAllNonLastPartsAtLeastFiveMiB(plans)
    }

    @Test
    fun legacyPlanner_img0307_isInvalid() {
        val size = 34_829_970L
        val legacy = MultipartPlanner.planPartsLegacy(size, eightMiB)
        assertFalse(MultipartPlanner.isValidPartPlan(legacy))
        assertTrue(MultipartPlanner.legacyPlanWasInvalid(size, eightMiB))
    }

    @Test
    fun justOverMultiplesOfEightMiB() {
        for (extra in listOf(1L, fiveMiB - 1, fiveMiB, fiveMiB + 1)) {
            val size = 3 * eightMiB + extra
            val plans = MultipartPlanner.planParts(size, eightMiB)
            assertEquals(size, plans.sumOf { it.length })
            assertAllNonLastPartsAtLeastFiveMiB(plans)
        }
    }

    @Test
    fun resumeIndexFromUploadedBytes() {
        val plans = MultipartPlanner.planParts(20L * 1024 * 1024, eightMiB)
        val idx = MultipartPlanner.resumePartIndex(plans, eightMiB)
        assertEquals(1, idx)
    }

    private fun assertAllNonLastPartsAtLeastFiveMiB(plans: List<MultipartPlanner.PartPlan>) {
        assertTrue(MultipartPlanner.isValidPartPlan(plans))
        if (plans.size <= 1) return
        for (i in 0 until plans.lastIndex) {
            assertTrue(
                "part ${plans[i].partNumber} length ${plans[i].length}",
                plans[i].length >= fiveMiB,
            )
        }
    }
}
