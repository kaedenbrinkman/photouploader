package com.photopipeline.app.upload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.photopipeline.app.R

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
        progress: UploadProgressSnapshot,
        useConnectedDevice: Boolean = true,
    ): ForegroundInfo {
        createChannel(context)
        val speedLine = formatUploadMbps(progress.averageBps)
        val etaLine = UploadEtaEstimator.formatEta(progress.etaSeconds)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle("Uploading from camera")
            .setContentText(
                "${formatUploadBytes(progress.bytesSent)} / ${formatUploadBytes(progress.totalBytes)} · " +
                    "${progress.filesDone}/${progress.filesTotal} files · $speedLine · $etaLine",
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setProgress(progress.totalBytes, progress.bytesSent, false)
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

private fun NotificationCompat.Builder.setProgress(
    totalBytes: Long,
    bytesSent: Long,
    indeterminate: Boolean,
): NotificationCompat.Builder {
    if (indeterminate) {
        return setProgress(0, 0, true)
    }
    if (totalBytes <= 0L) {
        return setProgress(0, 0, false)
    }
    if (totalBytes <= Int.MAX_VALUE) {
        return setProgress(totalBytes.toInt(), bytesSent.coerceIn(0L, totalBytes).toInt(), false)
    }
    val max = 1000
    val progress = ((bytesSent * max) / totalBytes).toInt().coerceIn(0, max)
    return setProgress(max, progress, false)
}
