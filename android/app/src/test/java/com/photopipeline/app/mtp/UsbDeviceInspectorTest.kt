package com.photopipeline.app.mtp

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDeviceInspectorTest {
    @Test
    fun isCanon_matchesVendorId() {
        val canon = mockDevice(vendorId = 0x04A9, productId = 0x1234, interfaceCount = 1)
        val other = mockDevice(vendorId = 0x054C, productId = 1, interfaceCount = 0)
        assertTrue(UsbDeviceInspector.isCanon(canon))
        assertFalse(UsbDeviceInspector.isCanon(other))
    }

    @Test
    fun isSupportedCamera_acceptsCanonOrMtpInterface() {
        val canon = mockDevice(vendorId = 0x04A9, productId = 1, interfaceCount = 0)
        assertTrue(UsbDeviceInspector.isSupportedCamera(canon))
        val mtpOnly = mockDevice(vendorId = 0x054C, productId = 1, interfaceCount = 1)
        every { mtpOnly.getInterface(0) } returns mockInterface(UsbConstants.USB_CLASS_STILL_IMAGE, 1, 0)
        assertTrue(UsbDeviceInspector.isSupportedCamera(mtpOnly))
        val other = mockDevice(vendorId = 0x054C, productId = 1, interfaceCount = 1)
        every { other.getInterface(0) } returns mockInterface(UsbConstants.USB_CLASS_VENDOR_SPEC, 0, 0)
        assertFalse(UsbDeviceInspector.isSupportedCamera(other))
    }

    @Test
    fun findMtpInterfaceIndex_detectsStillImageSubclass() {
        val device = mockDevice(vendorId = 0x04A9, productId = 1, interfaceCount = 2)
        every { device.getInterface(0) } returns mockInterface(UsbConstants.USB_CLASS_VENDOR_SPEC, 0, 0)
        every { device.getInterface(1) } returns mockInterface(UsbConstants.USB_CLASS_STILL_IMAGE, 1, 0)
        assertEquals(1, UsbDeviceInspector.findMtpInterfaceIndex(device))
    }

    private fun mockDevice(vendorId: Int, productId: Int, interfaceCount: Int): UsbDevice {
        val d = mockk<UsbDevice>()
        every { d.vendorId } returns vendorId
        every { d.productId } returns productId
        every { d.interfaceCount } returns interfaceCount
        every { d.deviceName } returns "usb:1,2"
        return d
    }

    private fun mockInterface(classId: Int, subclass: Int, protocol: Int): android.hardware.usb.UsbInterface {
        val i = mockk<android.hardware.usb.UsbInterface>()
        every { i.interfaceClass } returns classId
        every { i.interfaceSubclass } returns subclass
        every { i.interfaceProtocol } returns protocol
        return i
    }
}
