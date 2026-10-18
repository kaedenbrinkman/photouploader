package com.photopipeline.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.photopipeline.app.mtp.MtpUsbSession
import com.photopipeline.app.ui.PhotoPipelineNavHost
import com.photopipeline.app.ui.theme.PhotoPipelineTheme

class MainActivity : ComponentActivity() {
    private val usbReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                /* permission result — Compose screen can call refreshConnection */
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestCanonUsbPermissionIfNeeded()
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }
        val app = application as PhotoPipelineApp
        setContent {
            PhotoPipelineTheme {
                PhotoPipelineNavHost(repository = app.repository)
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private fun requestCanonUsbPermissionIfNeeded() {
        val usb = getSystemService(UsbManager::class.java)
        val device = usb.deviceList.values.firstOrNull { it.vendorId == MtpUsbSession.CANON_VENDOR_ID }
            ?: return
        if (usb.hasPermission(device)) return
        val pi = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        usb.requestPermission(device, pi)
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.photopipeline.app.USB_PERMISSION"
    }
}
