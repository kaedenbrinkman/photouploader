package com.photopipeline.app.mtp

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

/** Pure helpers for logging / tests (no Context). */
object UsbDeviceInspector {
    data class InterfaceSummary(
        val index: Int,
        val className: String,
        val subclass: Int,
        val protocol: Int,
        val isMtp: Boolean,
    )

    fun describe(device: UsbDevice): String {
        val ifaces = (0 until device.interfaceCount).map { describeInterface(device, it) }
        return buildString {
            append("name=${device.deviceName} vid=0x${device.vendorId.toString(16)} ")
            append("pid=0x${device.productId.toString(16)} ")
            append("ifaces=${device.interfaceCount} ")
            append(ifaces.joinToString { "[$it]" })
        }
    }

    fun describeInterface(device: UsbDevice, index: Int): InterfaceSummary {
        val intf = device.getInterface(index)
        val isMtp = intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE &&
            intf.interfaceSubclass == 1
        val className = when (intf.interfaceClass) {
            UsbConstants.USB_CLASS_STILL_IMAGE -> "STILL_IMAGE"
            UsbConstants.USB_CLASS_VIDEO -> "VIDEO"
            UsbConstants.USB_CLASS_VENDOR_SPEC -> "VENDOR"
            else -> "0x${intf.interfaceClass.toString(16)}"
        }
        return InterfaceSummary(
            index = index,
            className = className,
            subclass = intf.interfaceSubclass,
            protocol = intf.interfaceProtocol,
            isMtp = isMtp,
        )
    }

    fun findMtpInterfaceIndex(device: UsbDevice): Int? {
        for (i in 0 until device.interfaceCount) {
            if (describeInterface(device, i).isMtp) return i
        }
        return null
    }

    fun isCanon(device: UsbDevice): Boolean = device.vendorId == 0x04A9

    /** Canon bodies or any USB still-image (MTP) interface we can open. */
    fun isSupportedCamera(device: UsbDevice): Boolean =
        isCanon(device) || findMtpInterfaceIndex(device) != null
}
