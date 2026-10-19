package com.photopipeline.app.api

import com.photopipeline.app.core.PipelineLog
import com.photopipeline.app.upload.UploadPart
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

class ApiClient(
    private val baseUrlProvider: () -> String,
    private val sessionTokenProvider: suspend () -> String?,
) {
    private val baseUrl: String
        get() = baseUrlProvider().trim().trimEnd('/')
    private val client = OkHttpClient.Builder().build()
    private val finalizeClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val estimateAdapter: JsonAdapter<EstimateResponse> = moshi.adapter(EstimateResponse::class.java)
    private val statusAdapter: JsonAdapter<ShootStatusResponse> = moshi.adapter(ShootStatusResponse::class.java)

    suspend fun fetchEstimate(
        photoCount: Int,
        videoCount: Int,
        totalBytesRaw: Long,
        transferAcceleration: Boolean = false,
    ): EstimateResponse = onIo {
        val body = JSONObject()
            .put("photoCount", photoCount)
            .put("videoCount", videoCount)
            .put("imageCount", photoCount + videoCount)
            .put("totalBytesRaw", totalBytesRaw)
            .putTransferAcceleration(transferAcceleration)
            .toString()
        val req = authorizedRequest("/shoots/estimate").post(body.toRequestBody(JSON)).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiRequestException("estimate failed: ${resp.code}", resp.code)
            estimateAdapter.fromJson(resp.body!!.string())
                ?: throw ApiRequestException("estimate parse failed")
        }
    }

    suspend fun deleteShoot(shootId: String) = onIo {
        val req = authorizedRequest("/shoots/$shootId").delete().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) failHttp("delete_shoot", resp)
        }
    }

    suspend fun abortMultipart(
        shootId: String,
        uploadId: String,
        key: String,
        transferAcceleration: Boolean = false,
    ) = onIo {
        val body = JSONObject()
            .put("action", "abort_multipart")
            .put("uploadId", uploadId)
            .put("key", key)
            .putTransferAcceleration(transferAcceleration)
            .toString()
        val req = authorizedRequest("/shoots/$shootId/presign").post(body.toRequestBody(JSON)).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) failHttp("abort_multipart", resp)
        }
    }

    suspend fun ensureServerShoot(shootId: String, albumTitle: String): Unit = onIo {
        val body = JSONObject().apply {
            put("action", "create_shoot")
            put("shootId", shootId)
            put("albumTitle", albumTitle)
        }.toString()
        val req = authorizedRequest("/shoots").post(body.toRequestBody(JSON)).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                failHttp("create_shoot", resp)
            }
            PipelineLog.i("api", "ensureServerShoot $shootId HTTP ${resp.code}")
        }
    }

    suspend fun createMultipart(
        shootId: String,
        sha256: String,
        originalName: String,
        transferAcceleration: Boolean = false,
    ): Pair<String, String> = onIo {
        val body = JSONObject()
            .put("action", "create_multipart")
            .put("sha256", sha256)
            .put("originalName", originalName)
            .putTransferAcceleration(transferAcceleration)
            .toString()
        val req = authorizedRequest("/shoots/$shootId/presign").post(body.toRequestBody(JSON)).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) failHttp("create_multipart", resp)
            val json = JSONObject(resp.body!!.string())
            json.getString("uploadId") to json.getString("key")
        }
    }

    suspend fun presignPart(
        shootId: String,
        sha256: String,
        partNumber: Int,
        uploadId: String,
        originalName: String,
        sizeBytes: Long,
        transferAcceleration: Boolean = false,
    ): PresignedPartUrl = onIo {
        val body = JSONObject()
            .put("action", "presign_part")
            .put("sha256", sha256)
            .put("partNumber", partNumber)
            .put("uploadId", uploadId)
            .put("originalName", originalName)
            .put("sizeBytes", sizeBytes)
            .putTransferAcceleration(transferAcceleration)
            .toString()
        val req = authorizedRequest("/shoots/$shootId/presign").post(body.toRequestBody(JSON)).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) failHttp("presign_part", resp)
            val json = JSONObject(resp.body!!.string())
            PresignedPartUrl(
                url = json.getString("url"),
                transferAcceleration = json.optBoolean("transferAcceleration", transferAcceleration),
            )
        }
    }

    suspend fun listParts(
        shootId: String,
        uploadId: String,
        key: String,
        transferAcceleration: Boolean = false,
    ): List<Pair<Int, String>> = onIo {
        val body = JSONObject()
            .put("action", "list_parts")
            .put("uploadId", uploadId)
            .put("key", key)
            .putTransferAcceleration(transferAcceleration)
            .toString()
        val req = authorizedRequest("/shoots/$shootId/presign").post(body.toRequestBody(JSON)).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) failHttp("list_parts", resp)
            val arr = JSONObject(resp.body!!.string()).getJSONArray("parts")
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(o.getInt("partNumber") to o.getString("eTag"))
                }
            }
        }
    }

    data class HeadObjectResult(val exists: Boolean, val contentLength: Long)

    suspend fun headObject(
        shootId: String,
        key: String,
        transferAcceleration: Boolean = false,
    ): HeadObjectResult = onIo {
        val body = JSONObject()
            .put("action", "head_object")
            .put("key", key)
            .putTransferAcceleration(transferAcceleration)
            .toString()
        val req = authorizedRequest("/shoots/$shootId/presign").post(body.toRequestBody(JSON)).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) failHttp("head_object", resp)
            val json = JSONObject(resp.body!!.string())
            HeadObjectResult(
                exists = json.optBoolean("exists", false),
                contentLength = json.optLong("contentLength", 0L),
            )
        }
    }

    suspend fun completeMultipart(
        shootId: String,
        sha256: String,
        key: String,
        uploadId: String,
        parts: List<UploadPart>,
        transferAcceleration: Boolean = false,
    ) = onIo {
        val partsJson = JSONArray().apply {
            parts.sortedBy { it.partNumber }.forEach { p ->
                put(JSONObject().put("PartNumber", p.partNumber).put("ETag", p.eTag))
            }
        }
        val body = JSONObject()
            .put("action", "complete_multipart")
            .put("sha256", sha256)
            .put("key", key)
            .put("uploadId", uploadId)
            .put("parts", partsJson)
            .putTransferAcceleration(transferAcceleration)
            .toString()
        val req = authorizedRequest("/shoots/$shootId/presign").post(body.toRequestBody(JSON)).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) failHttp("complete_multipart", resp)
        }
    }

    suspend fun uploadManifest(shootId: String, manifestJson: String, transferAcceleration: Boolean = false) = onIo {
        val presignBody = JSONObject()
            .put("action", "presign_manifest")
            .putTransferAcceleration(transferAcceleration)
            .toString()
        val presignReq = authorizedRequest("/shoots/$shootId/presign").post(presignBody.toRequestBody(JSON)).build()
        val url = client.newCall(presignReq).execute().use { resp ->
            if (!resp.isSuccessful) failHttp("presign_manifest", resp)
            JSONObject(resp.body!!.string()).getString("url")
        }
        val put = Request.Builder()
            .url(url)
            .put(manifestJson.toRequestBody(MANIFEST_JSON))
            .build()
        client.newCall(put).execute().use { resp ->
            if (!resp.isSuccessful) failHttp("manifest PUT", resp)
        }
    }

    suspend fun finalizeShoot(shootId: String) = onIo {
        val req = authorizedRequest("/shoots/$shootId/finalize").post("{}".toRequestBody(JSON)).build()
        finalizeClient.newCall(req).execute().use { resp ->
            if (resp.code !in 200..202) failFinalize(resp)
        }
    }

    suspend fun pollStatusForProcessingStart(
        shootId: String,
        attempts: Int = 12,
        delayMs: Long = 2_500L,
    ): ShootStatusResponse? {
        repeat(attempts) {
            val status = runCatching { fetchStatus(shootId) }.getOrNull()
            if (status != null && FinalizeStartRecovery.processingAppearsStarted(status)) {
                return status
            }
            delay(delayMs)
        }
        return null
    }

    suspend fun fetchStatus(shootId: String): ShootStatusResponse = onIo {
        val req = authorizedRequest("/shoots/$shootId/status").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) failHttp("status", resp)
            statusAdapter.fromJson(resp.body!!.string())!!
        }
    }

    suspend fun exchangeGoogleAuth(serverAuthCode: String): GoogleAuthResponse = onIo {
        val body = JSONObject()
            .put("serverAuthCode", serverAuthCode)
            .toString()
        val req = Request.Builder()
            .url("$baseUrl/auth/google")
            .post(body.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            PipelineLog.i("auth", "POST /auth/google HTTP ${resp.code}")
            if (!resp.isSuccessful) {
                throw ApiRequestException("auth exchange failed: ${resp.code}", resp.code)
            }
            val json = JSONObject(resp.body!!.string())
            GoogleAuthResponse(
                sessionToken = json.getString("sessionToken"),
                expiresIn = json.getLong("expiresIn"),
                sub = json.getString("sub"),
                email = json.optString("email").ifBlank { null },
                gphotosLinked = json.optBoolean("gphotosLinked", false),
            )
        }
    }

    /** All OkHttp [execute] calls run here so callers are safe from the main thread. */
    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private suspend fun authorizedRequest(path: String): Request.Builder {
        val token = sessionTokenProvider() ?: throw NotSignedInException()
        return Request.Builder()
            .url("$baseUrl$path")
            .header("Authorization", "Bearer $token")
    }

    private fun JSONObject.putTransferAcceleration(enabled: Boolean): JSONObject {
        if (enabled) put("transferAcceleration", true)
        return this
    }

    private fun failFinalize(resp: Response): Nothing {
        val body = resp.body?.string().orEmpty()
        var ec2: String? = null
        var detail: String? = null
        var responseState: String? = null
        var missingCount: Int? = null
        runCatching {
            val o = JSONObject(body)
            val errField = o.optString("error").takeIf { it.isNotBlank() }
            ec2 = o.optString("ec2ErrorCode").takeIf { it.isNotBlank() } ?: errField
            detail = o.optString("detail").takeIf { it.isNotBlank() } ?: errField
            responseState = o.optString("state").takeIf { it.isNotBlank() }
            if (o.has("missingCount")) {
                missingCount = o.optInt("missingCount").takeIf { it > 0 }
            } else {
                missingCount = o.optJSONArray("missing")?.length()?.takeIf { it > 0 }
            }
        }
        val friendly = FinalizeErrorMessages.friendly(
            ec2ErrorCode = ec2,
            detail = detail,
            httpCode = resp.code,
            responseState = responseState,
            missingUploadCount = missingCount,
        )
        PipelineLog.w("api", "finalize HTTP ${resp.code} ec2=$ec2 detail=$detail")
        throw ApiRequestException(friendly, resp.code, ec2, responseState, missingCount)
    }

    private fun failHttp(route: String, resp: Response): Nothing {
        val body = resp.body?.string().orEmpty()
        val snippet = body.take(240).replace('\n', ' ')
        var errorCode: String? = null
        runCatching {
            errorCode = JSONObject(body).optString("error").takeIf { it.isNotBlank() }
        }
        val msg = "$route HTTP ${resp.code}${if (snippet.isNotBlank()) ": $snippet" else ""}"
        PipelineLog.w("api", msg)
        throw ApiRequestException(msg, resp.code, errorCode)
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val MANIFEST_JSON = "application/json".toMediaType()
    }
}

data class PresignedPartUrl(val url: String, val transferAcceleration: Boolean)
