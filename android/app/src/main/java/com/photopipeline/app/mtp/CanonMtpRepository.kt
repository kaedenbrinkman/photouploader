package com.photopipeline.app.mtp

import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo as AndroidMtpObjectInfo
import com.photopipeline.app.core.PipelineLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.math.min

class CanonMtpRepository(
    private val usb: UsbCameraManager,
) : MtpRepository {
    private val _state = MutableStateFlow(MtpConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MtpConnectionState> = _state
    override val usbAccessState: StateFlow<UsbAccessState> = usb.accessState

    override fun refreshConnection(): MtpConnectionState {
        _state.value = MtpConnectionState.CONNECTING
        val access = usb.refreshProbe(log = true)
        val mapped = when (access) {
            UsbAccessState.READY -> MtpConnectionState.CONNECTED
            UsbAccessState.PERMISSION_REQUIRED -> MtpConnectionState.ERROR
            UsbAccessState.PERMISSION_DENIED -> MtpConnectionState.ERROR
            UsbAccessState.OPEN_FAILED -> MtpConnectionState.ERROR
            UsbAccessState.NO_DEVICE -> MtpConnectionState.DISCONNECTED
        }
        _state.value = mapped
        return mapped
    }

    override suspend fun listCr3Objects(): List<MtpObjectInfo> = withContext(Dispatchers.IO) {
        PipelineLog.d("mtp", "listCr3Objects() begin")
        val mtp = usb.openMtpDevice() ?: run {
            PipelineLog.w("mtp", "listCr3Objects: could not open MTP")
            return@withContext emptyList()
        }
        try {
            val list = enumerateMedia(mtp)
            PipelineLog.d("mtp", "listCr3Objects: found ${list.size} media files")
            list
        } finally {
            mtp.close()
        }
    }

    override suspend fun getThumbnail(info: MtpObjectInfo): ByteArray? = withContext(Dispatchers.IO) {
        val mtp = usb.openMtpDevice() ?: return@withContext null
        try {
            mtp.getThumbnail(info.handle.toInt())
        } finally {
            mtp.close()
        }
    }

    override suspend fun importObject(
        info: MtpObjectInfo,
        destPath: String,
        onProgress: (Long) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val dest = File(destPath)
        dest.parentFile?.mkdirs()
        val mtp = usb.openMtpDevice() ?: throw IOException("MTP not connected — check USB permission")
        try {
            importWithResume(mtp, info, dest, onProgress)
        } finally {
            mtp.close()
        }
        sha256File(dest)
    }

    private fun enumerateMedia(device: MtpDevice): List<MtpObjectInfo> {
        val out = mutableListOf<MtpObjectInfo>()
        val storageIds = device.storageIds ?: return emptyList()
        for (storageId in storageIds) {
            val handles = device.getObjectHandles(
                storageId,
                MtpConstants.FORMAT_UNDEFINED,
                PARENT_ROOT,
            ) ?: continue
            for (handle in handles) {
                val objInfo = getObjectInfoWithRetry(device, handle) ?: continue
                val name = objInfo.name ?: continue
                if (!isMediaFile(name)) continue
                val captureMs = objInfo.dateCreated.toLong() * 1000L
                out += MtpObjectInfo(
                    storageId = storageId.toLong(),
                    handle = handle.toLong(),
                    filename = name,
                    sizeBytes = objInfo.compressedSize.toLong(),
                    captureTimeEpochMs = captureMs,
                )
            }
        }
        return out
    }

    private fun getObjectInfoWithRetry(device: MtpDevice, handle: Int, attempts: Int = 3): AndroidMtpObjectInfo? {
        repeat(attempts) { attempt ->
            val info = device.getObjectInfo(handle)
            if (info != null) return info
            if (attempt < attempts - 1) Thread.sleep(50L * (attempt + 1))
        }
        return null
    }

    private fun isMediaFile(name: String): Boolean {
        val u = name.uppercase()
        return u.endsWith(".CR3") || u.endsWith(".CR2") || u.endsWith(".JPG") || u.endsWith(".JPEG") ||
            u.endsWith(".MOV") || u.endsWith(".MP4")
    }

    private fun importWithResume(
        device: MtpDevice,
        info: MtpObjectInfo,
        dest: File,
        onProgress: (Long) -> Unit,
    ) {
        val handle = info.handle.toInt()
        var offset = dest.length().coerceAtLeast(0L)
        if (offset > info.sizeBytes) {
            dest.delete()
            offset = 0
        }
        if (offset == info.sizeBytes) {
            onProgress(offset)
            return
        }
        if (offset > 0) {
            resumePartial(device, handle, dest, offset, info.sizeBytes, onProgress)
            return
        }
        importFullWithRetry(device, handle, dest.absolutePath, info.sizeBytes, onProgress)
    }

    private fun resumePartial(
        device: MtpDevice,
        handle: Int,
        dest: File,
        startOffset: Long,
        totalSize: Long,
        onProgress: (Long) -> Unit,
    ) {
        var offset = startOffset
        RandomAccessFile(dest, "rw").use { raf ->
            while (offset < totalSize) {
                val chunk = min(PARTIAL_CHUNK_BYTES, totalSize - offset)
                val buf = ByteArray(chunk.toInt())
                val read = device.getPartialObject(handle, offset, chunk, buf)
                if (read <= 0) throw IOException("getPartialObject failed at offset $offset")
                raf.seek(offset)
                raf.write(buf, 0, read.toInt())
                offset += read
                onProgress(offset)
            }
        }
    }

    private fun importFullWithRetry(
        device: MtpDevice,
        handle: Int,
        destPath: String,
        expectedSize: Long,
        onProgress: (Long) -> Unit,
    ) {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val ok = device.importFile(handle, destPath)
                if (!ok) throw IOException("importFile returned false")
                val size = File(destPath).length()
                if (size != expectedSize) {
                    throw IOException("size mismatch after import: $size vs $expectedSize")
                }
                onProgress(size)
                return
            } catch (e: Exception) {
                last = e as? Exception ?: Exception(e)
                File(destPath).delete()
                if (attempt < 2) Thread.sleep(100L * (attempt + 1))
            }
        }
        throw last ?: IOException("import failed")
    }

    private fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PARTIAL_CHUNK_BYTES = 512 * 1024L
        private const val PARENT_ROOT = 0xFFFFFFFF.toInt()
    }
}
