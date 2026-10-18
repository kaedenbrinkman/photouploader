package com.photopipeline.app.mtp

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice

/** Opens a Canon (0x04A9) [MtpDevice] when USB permission is already granted. */
class MtpUsbSession(private val context: Context) {
    private val usbManager = context.getSystemService(UsbManager::class.java)

    fun findCanonDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.vendorId == CANON_VENDOR_ID }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun openCanonMtp(): MtpDevice? {
        val device = findCanonDevice() ?: return null
        if (!usbManager.hasPermission(device)) return null
        val connection = usbManager.openDevice(device) ?: return null
        val mtp = MtpDevice(device)
        return if (mtp.open(connection)) mtp else {
            connection.close()
            null
        }
    }

    companion object {
        const val CANON_VENDOR_ID = 0x04A9
    }
}

fun MtpDevice.useMtp(block: (MtpDevice) -> Unit) {
    try {
        block(this)
    } finally {
        close()
    }
}
