package com.photopipeline.app.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaKindTest {
    @Test
    fun detectsVideoExtensions() {
        assertEquals(MediaKind.Video, MediaKinds.fromFileName("clip.MP4"))
        assertEquals(MediaKind.Photo, MediaKinds.fromFileName("IMG.CR3"))
    }
}
