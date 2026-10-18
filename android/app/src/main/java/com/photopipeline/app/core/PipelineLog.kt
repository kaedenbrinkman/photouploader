package com.photopipeline.app.core

import android.util.Log
import com.photopipeline.app.mtp.UsbDebugLog

/** Logcat + on-screen USB debug ring buffer for field diagnostics. */
object PipelineLog {
    private const val TAG = "PhotoPipeline"

    fun d(step: String, message: String) {
        Log.d(TAG, "[$step] $message")
        UsbDebugLog.log("[$step] $message")
    }

    fun w(step: String, message: String, tr: Throwable? = null) {
        Log.w(TAG, "[$step] $message", tr)
        UsbDebugLog.log("[$step] $message${tr?.message?.let { ": $it" } ?: ""}")
    }

    fun e(step: String, message: String, tr: Throwable? = null) {
        Log.e(TAG, "[$step] $message", tr)
        UsbDebugLog.log("[$step] ERROR $message${tr?.message?.let { ": $it" } ?: ""}")
    }
}
