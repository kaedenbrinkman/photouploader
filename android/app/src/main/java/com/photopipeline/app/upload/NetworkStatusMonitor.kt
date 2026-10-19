package com.photopipeline.app.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

data class NetworkSnapshot(
    val type: String,
    val ssid: String?,
    val metered: Boolean,
    val validated: Boolean,
    val captivePortal: Boolean,
    val online: Boolean,
)

class NetworkStatusMonitor(context: Context) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun snapshot(): NetworkSnapshot {
        val network = cm.activeNetwork
        if (network == null) {
            return NetworkSnapshot("none", null, false, false, false, false)
        }
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkSnapshot("none", null, false, false, false, false)
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val captive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        } else {
            false
        }
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return NetworkSnapshot(type, null, metered, validated, captive, true)
    }

    fun canUpload(wifiOnly: Boolean, allowCellular: Boolean): Boolean {
        val s = snapshot()
        if (!s.online) return false
        if (s.captivePortal) return false
        return when (s.type) {
            "Wi-Fi", "ethernet" -> true
            "cellular" -> allowCellular && !wifiOnly
            else -> !wifiOnly
        }
    }
}
