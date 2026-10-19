package com.photopipeline.app.ui

import org.json.JSONObject

data class ProcessingProgress(
    val renderedPhotos: Int,
    val photoCount: Int,
    val publishedPhotos: Int,
    val publishedVideos: Int,
    val videoCount: Int,
)

fun capProcessingProgress(
    renderedPhotos: Int,
    photoCount: Int,
    publishedPhotos: Int,
    publishedVideos: Int,
    videoCount: Int,
): ProcessingProgress {
    val photos = photoCount.coerceAtLeast(0)
    val videos = videoCount.coerceAtLeast(0)
    return ProcessingProgress(
        renderedPhotos = renderedPhotos.coerceIn(0, if (photos > 0) photos else Int.MAX_VALUE),
        photoCount = photos,
        publishedPhotos = publishedPhotos.coerceIn(0, if (photos > 0) photos else Int.MAX_VALUE),
        publishedVideos = publishedVideos.coerceIn(0, if (videos > 0) videos else Int.MAX_VALUE),
        videoCount = videos,
    )
}

fun parseProcessingProgress(json: String?): ProcessingProgress? {
    if (json.isNullOrBlank()) return null
    return try {
        val o = JSONObject(json)
        val legacyTotal = o.optInt("totalFiles")
        val legacyRendered = o.optInt("renderedCount")
        val legacyPublished = o.optInt("publishedCount")
        val videoCount = o.optInt("videoCount")
        val explicitPhotos = o.optInt("photoCount", -1)
        val photoCount = when {
            explicitPhotos >= 0 -> explicitPhotos
            videoCount > 0 && legacyTotal > 0 -> (legacyTotal - videoCount).coerceAtLeast(0)
            else -> legacyTotal
        }
        capProcessingProgress(
            renderedPhotos = o.optInt("renderedPhotoCount", legacyRendered),
            photoCount = photoCount,
            publishedPhotos = o.optInt("publishedPhotoCount", legacyPublished),
            publishedVideos = o.optInt("publishedVideoCount"),
            videoCount = videoCount,
        )
    } catch (_: Exception) {
        null
    }
}

fun formatProcessingProgressLine(progress: ProcessingProgress): String {
    val photoLine = "Rendered ${progress.renderedPhotos} of ${progress.photoCount} photos"
    return if (progress.videoCount > 0) {
        "$photoLine · Published ${progress.publishedVideos} of ${progress.videoCount} videos"
    } else {
        "$photoLine · Published ${progress.publishedPhotos} of ${progress.photoCount} photos"
    }
}
