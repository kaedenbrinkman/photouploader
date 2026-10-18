package com.photopipeline.app.upload

import android.mtp.MtpDevice
import com.photopipeline.app.core.PipelineLog
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/** Holds [MtpDevice] across MTP session recovery during streaming reads. */
class MtpDeviceRef(var device: MtpDevice)

object MtpPartReader {
    const val SLICE_BYTES: Int = 1024 * 1024
    private const val UINT32_MAX: Long = 0xFFFFFFFFL

    /**
     * Reads [length] bytes from the camera object into [buffer] starting at index 0.
     * Large reads are split into [SLICE_BYTES] slices. On failure, [recoverSession] may reopen USB/MTP once per slice.
     */
    fun readPart(
        deviceRef: MtpDeviceRef,
        objectHandle: Int,
        offset: Long,
        length: Int,
        buffer: ByteArray,
        recoverSession: (() -> MtpDevice?)? = null,
    ): Int {
        require(length <= buffer.size) { "buffer too small: need $length have ${buffer.size}" }
        if (offset < 0) throw IOException("MTP read failed at offset $offset: negative offset")
        if (length == 0) return 0

        var total = 0
        while (total < length) {
            val sliceLen = minOf(SLICE_BYTES, length - total)
            val sliceOffset = offset + total
            val read = readSlice(
                deviceRef = deviceRef,
                objectHandle = objectHandle,
                offset = sliceOffset,
                length = sliceLen,
                buffer = buffer,
                bufferOffset = total,
                recoverSession = recoverSession,
            )
            if (read <= 0) {
                throw IOException("MTP read failed at offset $sliceOffset")
            }
            total += read
        }
        return total
    }

    /** @deprecated Use [MtpDeviceRef] overload for session recovery. */
    fun readPart(
        device: MtpDevice,
        objectHandle: Int,
        offset: Long,
        length: Int,
        buffer: ByteArray,
    ): Int = readPart(MtpDeviceRef(device), objectHandle, offset, length, buffer, null)

    internal fun preferDirectGetPartialObject(offset: Long, length: Int): Boolean =
        length >= 0 && offset + length.toLong() <= UINT32_MAX

    internal fun sliceLengths(totalLength: Int, sliceBytes: Int = SLICE_BYTES): List<Int> {
        val out = mutableListOf<Int>()
        var remaining = totalLength
        while (remaining > 0) {
            val n = minOf(sliceBytes, remaining)
            out += n
            remaining -= n
        }
        return out
    }

    internal fun unwrapInvokeFailure(t: Throwable): Throwable {
        var cur: Throwable = t
        while (cur is InvocationTargetException && cur.cause != null) {
            cur = cur.cause!!
        }
        return cur
    }

    private fun readSlice(
        deviceRef: MtpDeviceRef,
        objectHandle: Int,
        offset: Long,
        length: Int,
        buffer: ByteArray,
        bufferOffset: Int,
        recoverSession: (() -> MtpDevice?)?,
    ): Int {
        var lastError: Throwable? = null
        repeat(if (recoverSession != null) 2 else 1) { attempt ->
            try {
                return readSliceOnce(deviceRef.device, objectHandle, offset, length, buffer, bufferOffset)
            } catch (t: Throwable) {
                lastError = unwrapInvokeFailure(t)
                PipelineLog.w(
                    "mtp-read",
                    "getPartialObject* failed handle=$objectHandle offset=$offset length=$length " +
                        "attempt=${attempt + 1}: ${lastError.message}",
                    lastError,
                )
                if (recoverSession != null && attempt == 0) {
                    val reopened = recoverSession()
                    if (reopened != null) {
                        deviceRef.device = reopened
                        PipelineLog.i("mtp-read", "MTP session reopened after read failure at offset $offset")
                    }
                }
            }
        }
        throw IOException(
            "MTP read failed at offset $offset",
            lastError,
        )
    }

    private fun readSliceOnce(
        device: MtpDevice,
        objectHandle: Int,
        offset: Long,
        length: Int,
        buffer: ByteArray,
        bufferOffset: Int,
    ): Int {
        val methods = if (preferDirectGetPartialObject(offset, length)) {
            listOf(ReadMethod.DIRECT_32, ReadMethod.REFLECT_64)
        } else {
            listOf(ReadMethod.REFLECT_64, ReadMethod.DIRECT_32)
        }
        var last: Throwable? = null
        for (method in methods) {
            try {
                return invokeReadMethod(device, method, objectHandle, offset, length, buffer, bufferOffset)
            } catch (t: Throwable) {
                last = unwrapInvokeFailure(t)
                PipelineLog.w(
                    "mtp-read",
                    "${method.name} failed handle=$objectHandle offset=$offset length=$length: ${last.message}",
                )
            }
        }
        throw IOException("MTP read failed at offset $offset", last)
    }

    private enum class ReadMethod { DIRECT_32, REFLECT_64 }

    private fun invokeReadMethod(
        device: MtpDevice,
        method: ReadMethod,
        objectHandle: Int,
        offset: Long,
        length: Int,
        buffer: ByteArray,
        bufferOffset: Int,
    ): Int {
        val tmp = if (bufferOffset == 0 && length == buffer.size) {
            buffer
        } else {
            ByteArray(length)
        }
        val read = when (method) {
            ReadMethod.DIRECT_32 -> {
                val n = device.getPartialObject(objectHandle, offset, length.toLong(), tmp).toInt()
                if (n < 0) throw IOException("getPartialObject returned $n")
                n
            }
            ReadMethod.REFLECT_64 -> invokeGetPartialObject64(device, objectHandle, offset, length, tmp)
        }
        if (read <= 0) throw IOException("MTP read failed at offset $offset")
        if (tmp !== buffer) {
            System.arraycopy(tmp, 0, buffer, bufferOffset, read)
        }
        return read
    }

    private fun invokeGetPartialObject64(
        device: MtpDevice,
        objectHandle: Int,
        offset: Long,
        length: Int,
        buffer: ByteArray,
    ): Int {
        return try {
            val m = MtpDevice::class.java.getMethod(
                "getPartialObject64",
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                ByteArray::class.java,
            )
            when (val result = m.invoke(device, objectHandle, offset, length.toLong(), buffer)) {
                is Int -> if (result < 0) throw IOException("getPartialObject64 returned $result") else result
                is Long -> if (result < 0) throw IOException("getPartialObject64 returned $result") else result.toInt()
                else -> throw IOException("getPartialObject64 unexpected return type")
            }
        } catch (e: NoSuchMethodException) {
            throw IOException("getPartialObject64 not available", e)
        } catch (e: InvocationTargetException) {
            throw unwrapInvokeFailure(e)
        }
    }
}
