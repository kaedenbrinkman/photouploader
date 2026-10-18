package com.photopipeline.app.upload

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadPartsTest {
    @Test
    fun mergeReplacesSamePartNumber() {
        val merged = UploadParts.merge(listOf(UploadPart(1, "a")), UploadPart(1, "b"))
        assertEquals(1, merged.size)
        assertEquals("b", merged[0].eTag)
    }

    @Test
    fun roundTripJson() {
        val parts = listOf(UploadPart(1, "etag1"), UploadPart(2, "etag2"))
        val parsed = UploadParts.parse(UploadParts.toJson(parts))
        assertEquals(parts, parsed)
    }
}
