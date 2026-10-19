package com.photopipeline.app.media

enum class MediaKind {
    Photo,
    Video,
}

object MediaKinds {
    fun fromFileName(name: String): MediaKind {
        val u = name.uppercase()
        return when {
            u.endsWith(".MP4") || u.endsWith(".MOV") || u.endsWith(".M4V") -> MediaKind.Video
            else -> MediaKind.Photo
        }
    }

    fun isVideoFileName(name: String): Boolean = fromFileName(name) == MediaKind.Video
}
