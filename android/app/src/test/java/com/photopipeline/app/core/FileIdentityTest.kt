package com.photopipeline.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FileIdentityTest {
    @Test
    fun keyChangesWithHandle() {
        val sha = "a".repeat(64)
        val k1 = fileIdentityKey(1, 1, 100, 1L, sha)
        val k2 = fileIdentityKey(1, 2, 100, 1L, sha)
        assertNotEquals(k1, k2)
    }

    @Test
    fun keyStable() {
        val sha = "b".repeat(64)
        assertEquals(
            fileIdentityKey(1, 42, 10, 99L, sha),
            fileIdentityKey(1, 42, 10, 99L, sha),
        )
    }
}
