package com.seamlesspassage.spa.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

sealed class Sip2Result {
    /** 借书允许（借书成功）。*/
    data class Allowed(val message: String = "借书成功") : Sip2Result()

    /** 借书不允许（借书失败或业务拒绝）。*/
    data class Denied(val message: String) : Sip2Result()
}

class Sip2Service {
    private val client = OkHttpClient()

    /**
     * 调用图书馆业务接口 /sip2_check：输入 reader_id + tags(EPC+UID)。
     */
    suspend fun check(readerId: String, tags: List<RfidTag>): Sip2Result {
        return withContext(Dispatchers.IO) {
            try {
                val endpoint = "http://127.0.0.1:8080/sip2_check"

                val tagsArray = JSONArray().apply {
                    for (tag in tags) {
                        put(JSONArray().put(tag.epc).put(tag.uid))
                    }
                }

                val payload = JSONObject().apply {
                    put("reader_id", readerId)
                    put("tags", tagsArray)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = payload.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Sip2Result.Denied("SIP2 接口调用失败: HTTP ${response.code}")
                    }

                    val responseBody = response.body?.string() ?: return@withContext Sip2Result.Denied("SIP2 接口返回为空")
                    val json = JSONObject(responseBody)

                    val error = json.optBoolean("error", false)
                    val borrowAllowed = json.optBoolean("borrow_allowed", false)
                    val message = json.optString("message", "")

                    if (!error && borrowAllowed) {
                        Sip2Result.Allowed(if (message.isNotBlank()) message else "借书成功")
                    } else {
                        Sip2Result.Denied(if (message.isNotBlank()) message else "借书失败")
                    }
                }
            } catch (e: Exception) {
                Sip2Result.Denied("SIP2 接口异常: ${e.message ?: "unknown"}")
            }
        }
    }
}
