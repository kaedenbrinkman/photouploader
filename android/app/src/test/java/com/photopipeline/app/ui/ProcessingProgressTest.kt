package com.photopipeline.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessingProgressTest {
    @Test
    fun formatLine_withVideos() {
        val line = formatProcessingProgressLine(
            ProcessingProgress(
                renderedPhotos = 10,
                photoCount = 12,
                publishedPhotos = 8,
                publishedVideos = 2,
                videoCount = 3,
            ),
        )
        assertEquals("Rendered 10 of 12 photos · Published 2 of 3 videos", line)
    }

    @Test
    fun capProcessingProgress_limitsCounts() {
        val p = capProcessingProgress(99, 5, 99, 99, 2)
        assertEquals(5, p.renderedPhotos)
        assertEquals(2, p.publishedVideos)
    }
}
