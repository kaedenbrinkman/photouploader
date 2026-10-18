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
import org.json.JSONArray
import org.json.JSONObject

class ApiClient(
    private val baseUrlProvider: () -> String,
    private val sessionTokenProvider: suspend () -> String?,
) {
    private val baseUrl: String
        get() = baseUrlProvider().trim().trimEnd('/')
    private val client = OkHttpClient.Builder().build()
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val estimateAdapter: JsonAdapter<EstimateResponse> = moshi.adapter(EstimateResponse::class.java)
    private val statusAdapter: JsonAdapter<ShootStatusResponse> = moshi.adapter(ShootStatusResponse::class.java)

    suspend fun fetchEstimate(
        imageCount: Int,
        totalBytesRaw: Long,
        transferAcceleration: Boolean = false,
    ): EstimateResponse {
        val body = JSONObject()
            .put("imageCount", imageCount)
            .put("totalBytesRaw", totalBytesRaw)
            .putTransferAcceleration(transferAcceleration)
            .toString()
        val req = authorizedRequest("/shoots/estimate").post(body.toRequestBody(JSON)).build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiRequestException("estimate failed: ${resp.code}", resp.code)
            estimateAdapter.fromJson(resp.body!!.string())
                ?: throw ApiRequestException("estimate parse failed")
        }
    }

    suspend fun createMultipart(
        shootId: String,
        sha256: String,
        originalName: String,
        transferAcceleration: Boolean = false,
    ): Pair<String, String> {
        val body = JSONObject()
            .put("action", "create_multipart")
            .put("sha256", sha256)
            .put("originalName", originalName)
            .putTransferAcceleration(transferAcceleration)
            .toString()
        val req = authorizedRequest("/shoots/$shootId/presign").post(body.toRequestBody(JSON)).build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("multipart failed")
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
    ): PresignedPartUrl {
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
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("presign part failed")
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
    ): List<Pair<Int, String>> {
        val body = JSONObject()
            .put("action", "list_parts")
            .put("uploadId", uploadId)
            .put("key", key)
            .putTransferAcceleration(transferAcceleration)
            .toString()
        val req = authorizedRequest("/shoots/$shootId/presign").post(body.toRequestBody(JSON)).build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("list parts failed")
            val arr = JSONObject(resp.body!!.string()).getJSONArray("parts")
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(o.getInt("partNumber") to o.getString("eTag"))
                }
            }
        }
    }

    suspend fun completeMultipart(
        shootId: String,
        sha256: String,
        key: String,
        uploadId: String,
        parts: List<UploadPart>,
        transferAcceleration: Boolean = false,
    ) {
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
            if (!resp.isSuccessful) error("complete multipart failed: ${resp.code}")
        }
    }

    suspend fun uploadManifest(shootId: String, manifestJson: String, transferAcceleration: Boolean = false) {
        val presignBody = JSONObject()
            .put("action", "presign_manifest")
            .putTransferAcceleration(transferAcceleration)
            .toString()
        val presignReq = authorizedRequest("/shoots/$shootId/presign").post(presignBody.toRequestBody(JSON)).build()
        val url = client.newCall(presignReq).execute().use { resp ->
            if (!resp.isSuccessful) error("presign manifest failed")
            JSONObject(resp.body!!.string()).getString("url")
        }
        val put = Request.Builder()
            .url(url)
            .put(manifestJson.toRequestBody(MANIFEST_JSON))
            .build()
        client.newCall(put).execute().use { resp ->
            if (!resp.isSuccessful) error("manifest PUT failed: ${resp.code}")
        }
    }

    suspend fun finalizeShoot(shootId: String) {
        val req = authorizedRequest("/shoots/$shootId/finalize").post("{}".toRequestBody(JSON)).build()
        client.newCall(req).execute().use { resp ->
            if (resp.code !in 200..202) error("finalize failed: ${resp.code}")
        }
    }

    suspend fun fetchStatus(shootId: String): ShootStatusResponse {
        val req = authorizedRequest("/shoots/$shootId/status").get().build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("status failed")
            statusAdapter.fromJson(resp.body!!.string())!!
        }
    }

    suspend fun exchangeGoogleAuth(serverAuthCode: String): GoogleAuthResponse {
        val body = JSONObject()
            .put("serverAuthCode", serverAuthCode)
            .toString()
        val req = Request.Builder()
            .url("$baseUrl/auth/google")
            .post(body.toRequestBody(JSON))
            .build()
        return client.newCall(req).execute().use { resp ->
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

    companion object {
        private val JSON = "application/json".toMediaType()
        private val MANIFEST_JSON = "application/json".toMediaType()
    }
}

data class PresignedPartUrl(val url: String, val transferAcceleration: Boolean)
