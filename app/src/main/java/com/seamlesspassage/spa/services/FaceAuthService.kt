package com.seamlesspassage.spa.services

import com.seamlesspassage.spa.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

sealed class FaceAuthResult {
    data class Success(val userId: String) : FaceAuthResult()
    object Failure : FaceAuthResult()
}

class FaceAuthService {
    private val client = OkHttpClient()

    suspend fun authenticate(faceImageBase64: String): FaceAuthResult {
        return withContext(Dispatchers.IO) {
            try {
                val endpoint = AppConfig.FACE_AUTH_ENDPOINT
                val payload = JSONObject().apply {
                    put("image_base64", faceImageBase64)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = payload.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext FaceAuthResult.Failure
                    }

                    val responseBody = response.body?.string() ?: return@withContext FaceAuthResult.Failure
                    val json = JSONObject(responseBody)
                    val readerId = json.optString("reader_id", "")
                    if (readerId.isNotEmpty()) {
                        FaceAuthResult.Success(userId = readerId)
                    } else {
                        FaceAuthResult.Failure
                    }
                }
            } catch (e: Exception) {
                FaceAuthResult.Failure
            }
        }
    }
}
