package com.photopipeline.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.photopipeline.app.mtp.CameraMtpSnapshot
import com.photopipeline.app.mtp.UsbAccessState

private val StorageWarningAmber = Color(0xFFFFB300)

@Composable
fun CameraConnectionCard(
    usbState: UsbAccessState,
    camera: CameraMtpSnapshot?,
    permissionHint: String,
    disconnectedHint: String,
    modifier: Modifier = Modifier,
) {
    val connected = usbState == UsbAccessState.READY && camera != null
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Default.CameraAlt,
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (connected) {
                    ConnectedCameraContent(camera!!)
                } else {
                    DisconnectedCameraContent(
                        usbState = usbState,
                        permissionHint = permissionHint,
                        disconnectedHint = disconnectedHint,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectedCameraContent(camera: CameraMtpSnapshot) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(camera.title, style = MaterialTheme.typography.titleMedium)
        camera.batteryPercent?.let { pct ->
            Text(
                "Battery $pct%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (camera.firmwareVersion.isNotBlank()) {
        Text(
            "fw ${camera.firmwareVersion}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    camera.maskedSerial?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (camera.storageVolumes.isEmpty()) {
        Text(
            "No card",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        camera.storageVolumes.forEach { vol ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (vol.displayLabel.isNotBlank() && vol.displayLabel != "Storage") {
                    Text(vol.displayLabel, style = MaterialTheme.typography.labelMedium)
                }
                LinearProgressIndicator(
                    progress = { vol.usedProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    vol.freeOfTotalText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (vol.isLowSpace) {
                        StorageWarningAmber
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun DisconnectedCameraContent(
    usbState: UsbAccessState,
    permissionHint: String,
    disconnectedHint: String,
) {
    Text("No camera connected", style = MaterialTheme.typography.titleMedium)
    val subtitle = when (usbState) {
        UsbAccessState.PERMISSION_REQUIRED -> permissionHint
        UsbAccessState.PERMISSION_DENIED -> "USB permission denied — reconnect and allow access."
        UsbAccessState.OPEN_FAILED -> "Could not open camera — try another cable or port."
        else -> disconnectedHint
    }
    Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
