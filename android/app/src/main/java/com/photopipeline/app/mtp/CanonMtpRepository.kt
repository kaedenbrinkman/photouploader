package com.photopipeline.app.mtp

import android.mtp.MtpDevice
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
        val mapped = when (usb.accessState.value) {
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
        val list = MtpMediaEnumerator.enumerateMedia(mtp)
        PipelineLog.d("mtp", "listCr3Objects: found ${list.size} media files")
        list
    }

    override suspend fun getThumbnail(info: MtpObjectInfo): ByteArray? = withContext(Dispatchers.IO) {
        val mtp = usb.openMtpDevice() ?: return@withContext null
        mtp.getThumbnail(info.handle.toInt())
    }

    override suspend fun importObject(
        info: MtpObjectInfo,
        destPath: String,
        onProgress: (Long) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val dest = File(destPath)
        dest.parentFile?.mkdirs()
        val mtp = usb.openMtpDevice() ?: throw IOException("MTP not connected — check USB permission")
        importWithResume(mtp, info, dest, onProgress)
        sha256File(dest)
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
    }
}
