package com.photopipeline.app.mtp

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.MessageDigest

class MockMtpRepository : MtpRepository {
    private val _state = MutableStateFlow(MtpConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MtpConnectionState> = _state
    private val _usb = MutableStateFlow(UsbAccessState.NO_DEVICE)
    override val usbAccessState: StateFlow<UsbAccessState> = _usb

    private val sampleObjects = listOf(
        MtpObjectInfo(1, 101, "IMG_0001.CR3", 20_000_000, 1_700_000_000_000),
        MtpObjectInfo(1, 102, "IMG_0002.CR3", 21_000_000, 1_700_000_100_000),
    )

    override fun refreshConnection(): MtpConnectionState {
        _state.value = MtpConnectionState.CONNECTED
        _usb.value = UsbAccessState.READY
        return _state.value
    }

    override suspend fun listCr3Objects(): List<MtpObjectInfo> {
        if (_state.value != MtpConnectionState.CONNECTED) refreshConnection()
        return sampleObjects
    }

    override suspend fun getThumbnail(info: MtpObjectInfo): ByteArray? = null

    override suspend fun importObject(
        info: MtpObjectInfo,
        destPath: String,
        onProgress: (Long) -> Unit,
    ): String {
        val file = File(destPath)
        file.parentFile?.mkdirs()
        val chunk = ByteArray(65536) { 0xAB.toByte() }
        var written = 0L
        file.outputStream().use { out ->
            while (written < info.sizeBytes) {
                val n = minOf(chunk.size.toLong(), info.sizeBytes - written).toInt()
                out.write(chunk, 0, n)
                written += n
                onProgress(written)
                delay(1)
            }
        }
        return sha256(file)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
