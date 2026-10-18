package com.photopipeline.app.mtp

import android.mtp.MtpConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MtpObjectFormatTest {
    @Test
    fun mediaByExtension() {
        assertTrue(MtpObjectFormat.isMediaFileName("IMG_0001.CR3"))
        assertTrue(MtpObjectFormat.isMediaFileName("photo.jpeg"))
        assertFalse(MtpObjectFormat.isMediaFileName("100CANON"))
    }

    @Test
    fun folderDetection() {
        assertTrue(MtpObjectFormat.isFolder(MtpConstants.FORMAT_ASSOCIATION, "DCIM"))
        assertTrue(MtpObjectFormat.isFolder(MtpConstants.FORMAT_UNDEFINED, "100CANON"))
        assertFalse(MtpObjectFormat.isFolder(MtpConstants.FORMAT_UNDEFINED, "IMG.CR3"))
    }
}
