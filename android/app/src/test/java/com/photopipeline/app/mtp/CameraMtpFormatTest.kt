package com.photopipeline.app.mtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraMtpFormatTest {
    @Test
    fun cameraDisplayTitle_usesModelWhenManufacturerIncluded() {
        assertEquals("Canon EOS R8", CameraMtpFormat.cameraDisplayTitle("Canon", "Canon EOS R8"))
    }

    @Test
    fun cameraDisplayTitle_joinsManufacturerAndModel() {
        assertEquals("Canon EOS R8", CameraMtpFormat.cameraDisplayTitle("Canon", "EOS R8"))
    }

    @Test
    fun maskSerialNumber_showsLastFourOnly() {
        assertEquals("S/N •••1234", CameraMtpFormat.maskSerialNumber("ABC1234567891234"))
    }

    @Test
    fun maskSerialNumber_tooShortReturnsNull() {
        assertNull(CameraMtpFormat.maskSerialNumber("123"))
        assertNull(CameraMtpFormat.maskSerialNumber(null))
    }

    @Test
    fun formatStorageGb_oneDecimalUnder100() {
        assertEquals("48.2 GB", CameraMtpFormat.formatStorageGb(48_200_000_000L))
        assertEquals("128 GB", CameraMtpFormat.formatStorageGb(128_000_000_000L))
    }

    @Test
    fun formatStorageFreeOfTotal() {
        val text = CameraMtpFormat.formatStorageFreeOfTotal(48_200_000_000L, 128_000_000_000L)
        assertEquals("48.2 GB free of 128 GB", text)
    }

    @Test
    fun storageUsedProgress() {
        val progress = CameraMtpFormat.storageUsedProgress(48_200_000_000L, 128_000_000_000L)
        assertTrue(progress > 0.62f && progress < 0.64f)
    }

    @Test
    fun isStorageLow_underTenPercentFree() {
        assertTrue(CameraMtpFormat.isStorageLow(9_000_000_000L, 128_000_000_000L))
        assertFalse(CameraMtpFormat.isStorageLow(20_000_000_000L, 128_000_000_000L))
    }
}
