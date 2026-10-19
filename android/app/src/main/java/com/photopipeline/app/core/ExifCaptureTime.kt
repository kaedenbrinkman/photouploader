package com.photopipeline.app.core

import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

object ExifCaptureTime {
    fun epochMsFromThumbnailJpeg(bytes: ByteArray): Long? {
        if (bytes.size < 4) return null
        return runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).let { exif ->
                CaptureTimeNormalizer.parseExifDateTimeToEpochMs(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                    ?: CaptureTimeNormalizer.parseExifDateTimeToEpochMs(exif.getAttribute(ExifInterface.TAG_DATETIME))
                    ?: CaptureTimeNormalizer.parseExifDateTimeToEpochMs(exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED))
            }
        }.getOrNull()
    }
}
