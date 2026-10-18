package com.photopipeline.app.core

import java.security.MessageDigest

/** Stable 64-hex id for S3 multipart keys before content SHA-256 is known. */
fun metadataUploadSha256(
    storageId: Long,
    objectHandle: Long,
    sizeBytes: Long,
    captureTimeEpochMs: Long,
): String {
    val raw = "upload:$storageId:$objectHandle:$sizeBytes:$captureTimeEpochMs"
    return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

fun cameraFileIdentityKey(
    storageId: Long,
    objectHandle: Long,
    sizeBytes: Long,
    captureTimeEpochMs: Long,
): String {
    val raw = "camera:$storageId:$objectHandle:$sizeBytes:$captureTimeEpochMs"
    return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(32)
}
