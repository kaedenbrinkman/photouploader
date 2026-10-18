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
import com.photopipeline.app.core.PipelineLog
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

    private val sessionLock = Any()
    private var sessionConnection: UsbDeviceConnection? = null
    private var sessionMtp: MtpDevice? = null
    private var sessionDevice: UsbDevice? = null

    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val permissionReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USB_PERMISSION) return
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                PipelineLog.d(
                    "usb-permission",
                    "Broadcast result granted=$granted device=${device?.let { UsbDeviceInspector.describe(it) } ?: "?"}",
                )
                if (!granted) {
                    _accessState.value = UsbAccessState.PERMISSION_DENIED
                }
                permissionCallback?.invoke(granted)
                permissionCallback = null
                refreshProbe(log = true)
            }
        }

    private val attachReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        PipelineLog.d(
                            "usb-attach",
                            "ACTION_USB_DEVICE_ATTACHED ${device?.let { UsbDeviceInspector.describe(it) } ?: "no extra"}",
                        )
                        if (device != null && UsbDeviceInspector.isCanon(device)) {
                            _selectedDevice.value = device
                            refreshProbe(log = true)
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        PipelineLog.d(
                            "usb-detach",
                            "ACTION_USB_DEVICE_DETACHED ${device?.deviceName ?: "?"} — closing MTP session",
                        )
                        closeMtpSession()
                        if (device != null && device == _selectedDevice.value) {
                            _selectedDevice.value = null
                        }
                        _accessState.value = UsbAccessState.NO_DEVICE
                    }
                }
            }
        }

    private var receiversRegistered = false

    fun start() {
        if (receiversRegistered) return
        PipelineLog.d("usb", "UsbCameraManager.start() registering receivers")
        val permFilter = IntentFilter(ACTION_USB_PERMISSION)
        val attachFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(permissionReceiver, permFilter, Context.RECEIVER_NOT_EXPORTED)
            appContext.registerReceiver(attachReceiver, attachFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(permissionReceiver, permFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(attachReceiver, attachFilter)
        }
        receiversRegistered = true
        logDeviceList("startup")
        refreshProbe(log = true)
    }

    fun stop() {
        if (!receiversRegistered) return
        closeMtpSession()
        appContext.unregisterReceiver(permissionReceiver)
        appContext.unregisterReceiver(attachReceiver)
        receiversRegistered = false
    }

    fun onUsbIntent(intent: Intent?) {
        PipelineLog.d("usb-intent", "onUsbIntent action=${intent?.action}")
        when (intent?.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                PipelineLog.d(
                    "usb-intent",
                    "Activity USB_DEVICE_ATTACHED ${device?.let { UsbDeviceInspector.describe(it) } ?: "?"}",
                )
                if (device != null) _selectedDevice.value = device
                refreshProbe(log = true)
                if (_accessState.value == UsbAccessState.PERMISSION_REQUIRED) {
                    requestPermission()
                }
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                closeMtpSession()
                _selectedDevice.value = null
                _accessState.value = UsbAccessState.NO_DEVICE
            }
        }
    }

    fun listUsbDevices(): List<UsbDevice> = usbManager.deviceList.values.toList()

    private fun logDeviceList(reason: String) {
        val devices = listUsbDevices()
        PipelineLog.d("usb", "deviceList ($reason): count=${devices.size}")
        if (devices.isEmpty()) {
            PipelineLog.w(
                "usb",
                "deviceList empty — if system shows the camera, com.android.mtp or Canon Camera Connect " +
                    "may hold the session; disconnect USB, force-stop those apps, reconnect and pick Photo Pipeline.",
            )
            _selectedDevice.value?.let { cached ->
                PipelineLog.d("usb", "Cached ATTACHED device: ${UsbDeviceInspector.describe(cached)}")
            }
        } else {
            devices.forEach { PipelineLog.d("usb", UsbDeviceInspector.describe(it)) }
        }
    }

    fun refreshProbe(log: Boolean = false): UsbAccessState {
        if (log) logDeviceList("probe")
        val canon = findCanonDevice()
        _selectedDevice.value = canon
        if (canon == null) {
            _accessState.value = UsbAccessState.NO_DEVICE
            if (log) PipelineLog.d("usb-probe", "state=NO_DEVICE (no Canon in list or cache)")
            return _accessState.value
        }
        val hasPerm = usbManager.hasPermission(canon)
        PipelineLog.d("usb-probe", "Canon ${canon.deviceName} hasPermission=$hasPerm")
        if (!hasPerm) {
            _accessState.value = UsbAccessState.PERMISSION_REQUIRED
            if (log) PipelineLog.d("usb-probe", "state=PERMISSION_REQUIRED")
            return _accessState.value
        }
        synchronized(sessionLock) {
            if (sessionMtp != null && sessionDevice?.deviceName == canon.deviceName) {
                _accessState.value = UsbAccessState.READY
                if (log) PipelineLog.d("usb-probe", "state=READY (existing MTP session)")
                return _accessState.value
            }
        }
        _accessState.value = if (acquireMtpDevice() != null) {
            UsbAccessState.READY
        } else {
            UsbAccessState.OPEN_FAILED
        }
        if (log) PipelineLog.d("usb-probe", "state=${_accessState.value}")
        return _accessState.value
    }

    fun requestPermission(onResult: ((Boolean) -> Unit)? = null) {
        PipelineLog.d("usb-permission", "requestPermission() invoked")
        val device = findCanonDevice()
        if (device == null) {
            PipelineLog.w("usb-permission", "requestPermission: no Canon device reference")
            onResult?.invoke(false)
            return
        }
        if (usbManager.hasPermission(device)) {
            PipelineLog.d("usb-permission", "Already granted for ${device.deviceName}")
            onResult?.invoke(true)
            refreshProbe(log = true)
            return
        }
        permissionCallback = onResult
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        PipelineLog.d("usb-permission", "Calling UsbManager.requestPermission for ${device.deviceName}")
        usbManager.requestPermission(device, pi)
    }

    suspend fun requestPermissionSuspend(): Boolean = suspendCancellableCoroutine { cont ->
        requestPermission { granted ->
            if (cont.isActive) cont.resume(granted)
        }
    }

    /** Returns an open [MtpDevice] tied to a single USB session; do not call [MtpDevice.close] from callers. */
    fun openMtpDevice(): MtpDevice? = acquireMtpDevice()

    fun acquireMtpDevice(): MtpDevice? = synchronized(sessionLock) {
        val device = findCanonDevice() ?: run {
            PipelineLog.w("mtp", "acquireMtpDevice: no device")
            return null
        }
        if (!usbManager.hasPermission(device)) {
            PipelineLog.w("mtp", "acquireMtpDevice: permission missing")
            _accessState.value = UsbAccessState.PERMISSION_REQUIRED
            return null
        }
        if (sessionMtp != null && sessionDevice?.deviceName == device.deviceName) {
            PipelineLog.d("mtp-session", "reuse open session for ${device.deviceName}")
            return sessionMtp
        }
        closeMtpSessionLocked()
        PipelineLog.d("mtp-session", "opening new session for ${device.deviceName}")
        val opened = openConnection(device) ?: run {
            _accessState.value = UsbAccessState.OPEN_FAILED
            return null
        }
        val mtp = MtpDevice(device)
        if (!mtp.open(opened.connection)) {
            PipelineLog.e("mtp", "MtpDevice.open returned false")
            opened.connection.close()
            _accessState.value = UsbAccessState.OPEN_FAILED
            return null
        }
        sessionConnection = opened.connection
        sessionMtp = mtp
        sessionDevice = device
        _accessState.value = UsbAccessState.READY
        val storageIds = mtp.storageIds
        PipelineLog.d("mtp", "MtpDevice.open OK storageIds=${storageIds?.joinToString() ?: "null"}")
        mtp
    }

    fun closeMtpSession() = synchronized(sessionLock) {
        closeMtpSessionLocked()
    }

    private fun closeMtpSessionLocked() {
        try {
            sessionMtp?.close()
        } catch (e: Exception) {
            PipelineLog.w("mtp-session", "MtpDevice.close: ${e.message}")
        }
        sessionMtp = null
        try {
            sessionConnection?.close()
        } catch (e: Exception) {
            PipelineLog.w("mtp-session", "connection.close: ${e.message}")
        }
        sessionConnection = null
        sessionDevice = null
    }

    private data class OpenedConnection(val connection: UsbDeviceConnection)

    private fun openConnection(device: UsbDevice): OpenedConnection? {
        PipelineLog.d("usb-open", "openDevice(${device.deviceName})")
        val connection = usbManager.openDevice(device) ?: run {
            PipelineLog.e("usb-open", "openDevice returned null")
            return null
        }
        val mtpIndex = UsbDeviceInspector.findMtpInterfaceIndex(device)
        if (mtpIndex == null) {
            PipelineLog.e("usb-open", "No MTP Still-Image interface on device")
            connection.close()
            return null
        }
        val intf = device.getInterface(mtpIndex)
        val claimed = connection.claimInterface(intf, true)
        PipelineLog.d("usb-open", "claimInterface(index=$mtpIndex class=${intf.interfaceClass} force=true) -> $claimed")
        if (!claimed) {
            connection.close()
            return null
        }
        return OpenedConnection(connection)
    }

    fun findCanonDevice(): UsbDevice? {
        listUsbDevices().firstOrNull { UsbDeviceInspector.isCanon(it) }?.let { return it }
        _selectedDevice.value?.takeIf { UsbDeviceInspector.isCanon(it) }?.let { cached ->
            PipelineLog.w("usb", "Using cached USB_DEVICE_ATTACHED Canon (not in current deviceList)")
            return cached
        }
        return null
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.photopipeline.app.action.USB_PERMISSION"
    }
}
