package com.photopipeline.app

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.photopipeline.app.auth.GoogleAuthCoordinator
import com.photopipeline.app.core.PipelineLog
import com.photopipeline.app.ui.PhotoPipelineNavHost
import com.photopipeline.app.ui.theme.PhotoPipelineTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
}
