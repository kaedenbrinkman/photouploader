package com.photopipeline.app.api

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
    private val baseUrl: String,
    private val idTokenProvider: suspend () -> String?,
) {
    private val client = OkHttpClient.Builder().build()
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val estimateAdapter: JsonAdapter<EstimateResponse> = moshi.adapter(EstimateResponse::class.java)
    private val statusAdapter: JsonAdapter<ShootStatusResponse> = moshi.adapter(ShootStatusResponse::class.java)

    suspend fun fetchEstimate(imageCount: Int, totalBytesRaw: Long): EstimateResponse {
        val body = JSONObject().put("imageCount", imageCount).put("totalBytesRaw", totalBytesRaw).toString()
        val req = authorizedRequest("/shoots/estimate").post(body.toRequestBody(JSON)).build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiRequestException("estimate failed: ${resp.code}", resp.code)
            estimateAdapter.fromJson(resp.body!!.string())
                ?: throw ApiRequestException("estimate parse failed")
        }
    }

    suspend fun createMultipart(shootId: String, sha256: String, originalName: String): Pair<String, String> {
        val body = JSONObject()
            .put("action", "create_multipart")
            .put("sha256", sha256)
            .put("originalName", originalName)
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
    ): String {
        val body = JSONObject()
            .put("action", "presign_part")
            .put("sha256", sha256)
            .put("partNumber", partNumber)
            .put("uploadId", uploadId)
            .put("originalName", originalName)
            .put("sizeBytes", sizeBytes)
            .toString()
        val req = authorizedRequest("/shoots/$shootId/presign").post(body.toRequestBody(JSON)).build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("presign part failed")
            JSONObject(resp.body!!.string()).getString("url")
        }
    }

    suspend fun listParts(shootId: String, uploadId: String, key: String): List<Pair<Int, String>> {
        val body = JSONObject()
            .put("action", "list_parts")
            .put("uploadId", uploadId)
            .put("key", key)
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
            .toString()
        val req = authorizedRequest("/shoots/$shootId/presign").post(body.toRequestBody(JSON)).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("complete multipart failed: ${resp.code}")
        }
    }

    suspend fun uploadManifest(shootId: String, manifestJson: String) {
        val presignBody = JSONObject().put("action", "presign_manifest").toString()
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

    fun photosOAuthStartUrl(userId: String): String = "$baseUrl/oauth/photos/start?userId=$userId"

    private suspend fun authorizedRequest(path: String): Request.Builder {
        val token = idTokenProvider() ?: throw NotSignedInException()
        return Request.Builder()
            .url("$baseUrl$path")
            .header("Authorization", "Bearer $token")
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val MANIFEST_JSON = "application/json".toMediaType()
    }
}
