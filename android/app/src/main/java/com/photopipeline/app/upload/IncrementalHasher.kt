package com.photopipeline.app.upload

import java.security.MessageDigest

class IncrementalHasher {
    private val sha256 = MessageDigest.getInstance("SHA-256")

    fun update(buffer: ByteArray, offset: Int, length: Int) {
        sha256.update(buffer, offset, length)
    }

    fun digestHex(): String = sha256.digest().joinToString("") { "%02x".format(it) }

    companion object {
        fun md5Base64(buffer: ByteArray, offset: Int, length: Int): String {
            val md5 = MessageDigest.getInstance("MD5")
            md5.update(buffer, offset, length)
            return java.util.Base64.getEncoder().encodeToString(md5.digest())
        }
    }
}
