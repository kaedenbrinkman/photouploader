package com.photopipeline.app.mtp

import android.content.Context
import android.hardware.usb.UsbManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * USB host / MTP stub for real hardware. Wire to android.mtp or canon-mtp-direct on device.
 */
class CanonMtpRepository(private val context: Context) : MtpRepository {
    private val _state = MutableStateFlow(MtpConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MtpConnectionState> = _state

    override suspend fun refreshConnection(): MtpConnectionState {
        val usb = context.getSystemService(UsbManager::class.java)
        val canon = usb.deviceList.values.firstOrNull { it.vendorId == 0x04A9 }
        _state.value = if (canon != null) MtpConnectionState.CONNECTED else MtpConnectionState.DISCONNECTED
        return _state.value
    }

    override suspend fun listCr3Objects(): List<MtpObjectInfo> {
        if (_state.value != MtpConnectionState.CONNECTED) return emptyList()
        // Real implementation: MtpDevice storage enumeration (see canon-mtp-direct)
        return emptyList()
    }

    override suspend fun importObject(
        info: MtpObjectInfo,
        destPath: String,
        onProgress: (Long) -> Unit,
    ): String {
        throw UnsupportedOperationException("MTP import requires on-device USB stack")
    }
}
