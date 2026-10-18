package com.photopipeline.app.core

import java.security.MessageDigest

fun fileIdentityKey(
    storageId: Long,
    objectHandle: Long,
    sizeBytes: Long,
    captureTimeEpochMs: Long,
    sha256: String,
): String {
    val raw = "$storageId:$objectHandle:$sizeBytes:$captureTimeEpochMs:${sha256.lowercase()}"
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}
