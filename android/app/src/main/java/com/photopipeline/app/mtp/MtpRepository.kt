package com.photopipeline.app.mtp

import kotlinx.coroutines.flow.StateFlow

/**
 * MTP access layer (Canon R8). Production implementation adapts canon-mtp-direct patterns;
 * tests use [MockMtpRepository].
 */
interface MtpRepository {
    val connectionState: StateFlow<MtpConnectionState>
    suspend fun refreshConnection(): MtpConnectionState
    suspend fun listCr3Objects(): List<MtpObjectInfo>
    suspend fun importObject(info: MtpObjectInfo, destPath: String, onProgress: (Long) -> Unit): String
}
