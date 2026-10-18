package com.photopipeline.app.mtp

import android.mtp.MtpConstants

/** MTP object format helpers (unit-testable, no device). */
object MtpObjectFormat {
    const val FORMAT_ALL_OBJECTS: Int = 0

    fun isMediaFileName(name: String): Boolean {
        val u = name.uppercase()
        return u.endsWith(".CR3") || u.endsWith(".CR2") || u.endsWith(".JPG") || u.endsWith(".JPEG") ||
            u.endsWith(".MOV") || u.endsWith(".MP4") || u.endsWith(".HEIC")
    }

    fun isFolder(format: Int, name: String): Boolean {
        if (format == MtpConstants.FORMAT_ASSOCIATION) return true
        if (format == MtpConstants.FORMAT_DEFINED) return false
        // Canon often reports folders as undefined (0x3000) with no file extension.
        if (!name.contains('.')) return true
        return false
    }

    fun formatHex(format: Int): String = "0x${format.toUInt().toString(16)}"
}
