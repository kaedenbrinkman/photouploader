package com.photopipeline.app.mtp

import kotlinx.coroutines.flow.StateFlow

interface MtpRepository {
    val connectionState: StateFlow<MtpConnectionState>
    val usbAccessState: StateFlow<UsbAccessState>
    fun refreshConnection(): MtpConnectionState
    suspend fun listCr3Objects(): List<MtpObjectInfo>
    suspend fun getThumbnail(info: MtpObjectInfo): ByteArray?
    suspend fun importObject(info: MtpObjectInfo, destPath: String, onProgress: (Long) -> Unit): String
}
