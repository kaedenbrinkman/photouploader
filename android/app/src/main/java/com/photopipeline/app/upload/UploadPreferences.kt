package com.photopipeline.app.upload

import android.content.Context
import android.content.SharedPreferences

class UploadPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("upload_prefs", Context.MODE_PRIVATE)

    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(v) = prefs.edit().putBoolean("wifi_only", v).apply()

    var allowCellular: Boolean
        get() = prefs.getBoolean("allow_cellular", false)
        set(v) = prefs.edit().putBoolean("allow_cellular", v).apply()

    var partSizeBytes: Long
        get() = prefs.getLong("part_size_bytes", MultipartPlanner.DEFAULT_PART_BYTES)
        set(v) = prefs.edit().putLong("part_size_bytes", v.coerceAtLeast(MultipartPlanner.S3_MIN_PART_BYTES)).apply()

    var maxConcurrentParts: Int
        get() = prefs.getInt("max_concurrent_parts", 0)
        set(v) = prefs.edit().putInt("max_concurrent_parts", v.coerceIn(0, 4)).apply()

    /** 0 = adaptive */
    fun effectiveConcurrency(errorRate: Double, speedBps: Long): Int {
        val configured = maxConcurrentParts
        if (configured in 1..4) return configured
        return when {
            errorRate > 0.25 || speedBps < 200_000 -> 1
            speedBps < 2_000_000 -> 2
            else -> 3
        }.coerceIn(1, 4)
    }

    var lastMeasuredUploadBps: Long
        get() = prefs.getLong("last_measured_upload_bps", 0)
        set(v) = prefs.edit().putLong("last_measured_upload_bps", v).apply()

    var transferAcceleration: Boolean
        get() = prefs.getBoolean("transfer_acceleration", false)
        set(v) = prefs.edit().putBoolean("transfer_acceleration", v).apply()
}
