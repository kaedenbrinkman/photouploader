package com.photopipeline.app.upload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

object UploadNotifications {
    private const val CHANNEL = "uploads"
    private const val NOTIFICATION_ID = 42

    fun foregroundInfo(
        context: Context,
        speedBps: Long = 0,
        etaSeconds: Long? = null,
        filesDone: Int = 0,
        filesTotal: Int = 0,
    ): ForegroundInfo {
        createChannel(context)
        val speedMbps = speedBps * 8.0 / 1_000_000.0
        val eta = etaSeconds?.let { " · ETA ${it / 60}m ${it % 60}s" } ?: ""
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle("Uploading from camera → S3")
            .setContentText(
                "Files $filesDone/$filesTotal · ${"%.1f".format(speedMbps)} Mbps$eta",
            )
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Uploads", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
