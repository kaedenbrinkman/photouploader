package com.photopipeline.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.photopipeline.app.auth.GoogleAuthCoordinator
import com.photopipeline.app.core.PipelineLog
import com.photopipeline.app.ui.PhotoPipelineNavHost
import com.photopipeline.app.ui.theme.PhotoPipelineTheme

class MainActivity : ComponentActivity() {
    private val requestPostNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        PipelineLog.i("activity", "POST_NOTIFICATIONS granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestUploadNotificationPermissionIfNeeded()
        PipelineLog.d("activity", "MainActivity.onCreate action=${intent?.action}")
        val app = application as PhotoPipelineApp
        app.usbCameraManager.onUsbIntent(intent)
        GoogleAuthCoordinator.tryCompleteFromIntent(this, intent)
        setContent {
            PhotoPipelineTheme {
                PhotoPipelineNavHost(
                    repository = app.repository,
                    usbCameraManager = app.usbCameraManager,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            (application as PhotoPipelineApp).usbCameraManager.onUsbIntent(intent)
        }
        GoogleAuthCoordinator.tryCompleteFromIntent(this, intent)
    }

    private fun requestUploadNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
