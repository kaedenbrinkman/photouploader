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

    fun foregroundInfo(context: Context): ForegroundInfo {
        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle("Uploading RAW files")
            .setContentText("Transfer to S3 in progress")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
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
