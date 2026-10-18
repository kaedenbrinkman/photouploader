package com.photopipeline.app.mtp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

enum class UsbAccessState {
    NO_DEVICE,
    PERMISSION_REQUIRED,
    PERMISSION_DENIED,
    OPEN_FAILED,
    READY,
}

class UsbCameraManager(private val appContext: Context) {
    private val usbManager = appContext.getSystemService(UsbManager::class.java)

    private val _accessState = MutableStateFlow(UsbAccessState.NO_DEVICE)
    val accessState: StateFlow<UsbAccessState> = _accessState.asStateFlow()

    private val _selectedDevice = MutableStateFlow<UsbDevice?>(null)
    val selectedDevice: StateFlow<UsbDevice?> = _selectedDevice.asStateFlow()

    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val permissionReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USB_PERMISSION) return
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                UsbDebugLog.log(
                    "USB permission result granted=$granted device=${device?.deviceName ?: "?"}",
                )
                if (!granted) {
                    _accessState.value = UsbAccessState.PERMISSION_DENIED
                }
                permissionCallback?.invoke(granted)
                permissionCallback = null
                refreshProbe(log = true)
            }
        }

    private var receiverRegistered = false

    fun start() {
        if (receiverRegistered) return
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(permissionReceiver, filter)
        }
        receiverRegistered = true
        refreshProbe(log = true)
    }

    fun stop() {
        if (!receiverRegistered) return
        appContext.unregisterReceiver(permissionReceiver)
        receiverRegistered = false
    }

    fun onUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            UsbDebugLog.log("USB_DEVICE_ATTACHED ${device?.let { UsbDeviceInspector.describe(it) } ?: "?"}")
            _selectedDevice.value = device ?: findCanonDevice()
            refreshProbe(log = true)
            if (_accessState.value == UsbAccessState.PERMISSION_REQUIRED) {
                requestPermission()
            }
        }
    }

    fun listUsbDevices(): List<UsbDevice> = usbManager.deviceList.values.toList()

    fun refreshProbe(log: Boolean = false): UsbAccessState {
        val devices = listUsbDevices()
        if (log) {
            UsbDebugLog.log("USB devices attached: ${devices.size}")
            devices.forEach { UsbDebugLog.log(UsbDeviceInspector.describe(it)) }
        }
        val canon = _selectedDevice.value?.takeIf { UsbDeviceInspector.isCanon(it) }
            ?: findCanonDevice()
        _selectedDevice.value = canon
        if (canon == null) {
            _accessState.value = UsbAccessState.NO_DEVICE
            return _accessState.value
        }
        if (!usbManager.hasPermission(canon)) {
            _accessState.value = UsbAccessState.PERMISSION_REQUIRED
            if (log) UsbDebugLog.log("Canon device found but permission missing")
            return _accessState.value
        }
        val opened = openConnection(canon)
        _accessState.value = if (opened != null) {
            opened.connection.close()
            UsbAccessState.READY
        } else {
            UsbAccessState.OPEN_FAILED
        }
        if (log) UsbDebugLog.log("Probe state=${_accessState.value}")
        return _accessState.value
    }

    fun requestPermission(onResult: ((Boolean) -> Unit)? = null) {
        val device = findCanonDevice()
        if (device == null) {
            UsbDebugLog.log("requestPermission: no Canon device")
            onResult?.invoke(false)
            return
        }
        if (usbManager.hasPermission(device)) {
            onResult?.invoke(true)
            refreshProbe(log = true)
            return
        }
        permissionCallback = onResult
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        UsbDebugLog.log("Requesting USB permission for ${device.deviceName}")
        usbManager.requestPermission(device, pi)
    }

    suspend fun requestPermissionSuspend(): Boolean = suspendCancellableCoroutine { cont ->
        requestPermission { granted ->
            if (cont.isActive) cont.resume(granted)
        }
    }

    fun openMtpDevice(): MtpDevice? {
        val device = findCanonDevice() ?: run {
            UsbDebugLog.log("openMtpDevice: no device")
            return null
        }
        if (!usbManager.hasPermission(device)) {
            UsbDebugLog.log("openMtpDevice: permission missing")
            _accessState.value = UsbAccessState.PERMISSION_REQUIRED
            return null
        }
        val opened = openConnection(device) ?: run {
            _accessState.value = UsbAccessState.OPEN_FAILED
            return null
        }
        val mtp = MtpDevice(device)
        return if (mtp.open(opened.connection)) {
            _accessState.value = UsbAccessState.READY
            UsbDebugLog.log("MtpDevice.open OK")
            mtp
        } else {
            UsbDebugLog.log("MtpDevice.open failed")
            opened.connection.close()
            _accessState.value = UsbAccessState.OPEN_FAILED
            null
        }
    }

    private data class OpenedConnection(val connection: UsbDeviceConnection)

    private fun openConnection(device: UsbDevice): OpenedConnection? {
        val connection = usbManager.openDevice(device) ?: run {
            UsbDebugLog.log("openDevice returned null")
            return null
        }
        val mtpIndex = UsbDeviceInspector.findMtpInterfaceIndex(device)
        if (mtpIndex == null) {
            UsbDebugLog.log("No MTP interface on device")
            connection.close()
            return null
        }
        val intf = device.getInterface(mtpIndex)
        val claimed = connection.claimInterface(intf, true)
        UsbDebugLog.log("claimInterface(index=$mtpIndex, force=true) -> $claimed")
        if (!claimed) {
            connection.close()
            return null
        }
        return OpenedConnection(connection)
    }

    fun findCanonDevice(): UsbDevice? =
        listUsbDevices().firstOrNull { UsbDeviceInspector.isCanon(it) }

    companion object {
        const val ACTION_USB_PERMISSION = "com.photopipeline.app.action.USB_PERMISSION"
    }
}
