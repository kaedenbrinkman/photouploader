package com.photopipeline.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory

@Composable
fun ShootThumbnailImage(bytes: ByteArray?) {
    if (bytes != null) {
        val bmp = remember(bytes) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(72.dp),
                contentScale = ContentScale.Crop,
            )
            return
        }
    }
    Icon(
        Icons.Default.Image,
        contentDescription = null,
        modifier = Modifier.fillMaxWidth().height(72.dp),
    )
}
