package com.photopipeline.app.upload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

object UploadNotifications {
    private const val CHANNEL = "uploads"
    const val NOTIFICATION_ID = 42

    /**
     * Camera→S3 uploads need [ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC] (network) and
     * [ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE] when reading from USB MTP.
     *
     * On Android 15+, `dataSync` foreground services are capped at **6 hours per day** per app;
     * very long shoot uploads may hit the limit and should rely on WorkManager retry/resume
     * (Room tracks multipart progress) rather than one uninterrupted FGS session.
     */
    fun foregroundInfo(
        context: Context,
        speedBps: Long = 0,
        etaSeconds: Long? = null,
        filesDone: Int = 0,
        filesTotal: Int = 0,
        useConnectedDevice: Boolean = true,
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
        var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (useConnectedDevice) {
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return ForegroundInfo(NOTIFICATION_ID, notification, serviceType)
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
