package com.photopipeline.app.upload

import android.mtp.MtpDevice
import java.io.IOException

object MtpPartReader {
    fun readPart(
        device: MtpDevice,
        objectHandle: Int,
        offset: Long,
        length: Int,
        buffer: ByteArray,
    ): Int {
        require(length <= buffer.size) { "buffer too small" }
        if (offset < 0) throw IOException("negative offset")
        for (methodName in listOf("getPartialObject64", "getPartialObject")) {
            invokeLongOffset(device, methodName, objectHandle, offset, length, buffer)?.let { return it }
        }
        throw IOException("No usable getPartialObject* for offset $offset")
    }

    private fun invokeLongOffset(
        device: MtpDevice,
        methodName: String,
        objectHandle: Int,
        offset: Long,
        length: Int,
        buffer: ByteArray,
    ): Int? {
        return try {
            val method = MtpDevice::class.java.getMethod(
                methodName,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                ByteArray::class.java,
            )
            when (val result = method.invoke(device, objectHandle, offset, length.toLong(), buffer)) {
                is Int -> if (result < 0) throw IOException("$methodName failed") else result
                is Long -> if (result < 0) throw IOException("$methodName failed") else result.toInt()
                else -> null
            }
        } catch (_: NoSuchMethodException) {
            null
        }
    }
}
