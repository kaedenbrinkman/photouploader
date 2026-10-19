package com.photopipeline.app.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.InvocationTargetException

class MtpPartReaderTest {
    @Test
    fun preferDirectGetPartialObject_whenWithin32BitRange() {
        assertTrue(MtpPartReader.preferDirectGetPartialObject(0, 8 * 1024 * 1024))
        assertTrue(MtpPartReader.preferDirectGetPartialObject(1000, MtpPartReader.SLICE_BYTES))
    }

    @Test
    fun preferReflect64_whenSpanCrosses32BitBoundary() {
        val offset = 0xFFFF_F000L
        val length = 0x2000
        assertFalse(MtpPartReader.preferDirectGetPartialObject(offset, length))
    }

    @Test
    fun sliceLengths_splitsEightMbPartIntoOneMbChunks() {
        val eightMb = 8 * 1024 * 1024
        assertEquals(
            List(8) { MtpPartReader.SLICE_BYTES },
            MtpPartReader.sliceLengths(eightMb),
        )
    }

    @Test
    fun sliceLengths_handlesRemainder() {
        assertEquals(
            listOf(MtpPartReader.SLICE_BYTES, 512),
            MtpPartReader.sliceLengths(MtpPartReader.SLICE_BYTES + 512),
        )
    }

    @Test
    fun unwrapInvokeFailure_exposesRootIOException() {
        val root = IOException("Failed to read data.")
        val wrapped = InvocationTargetException(root)
        val unwrapped = MtpPartReader.unwrapInvokeFailure(wrapped)
        assertTrue(unwrapped is IOException)
        assertEquals("Failed to read data.", unwrapped.message)
    }
}
