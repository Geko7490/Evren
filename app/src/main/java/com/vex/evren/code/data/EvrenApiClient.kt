package com.vex.evren.code.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class EvrenModel(val id: String, val displayName: String = id)

class EvrenApiClient(
    private val apiKey: () -> String?,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) {
    suspend fun listModels(): List<EvrenModel> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/models")
            .header("Authorization", "Bearer ${requireKey()}")
            .header("Accept", "application/json")
            .get()
            .build()
        val json = execute(request)
        val data = json.optJSONArray("data") ?: JSONArray()
        buildList {
            for (index in 0 until data.length()) {
                val id = data.optJSONObject(index)?.optString("id").orEmpty()
                if (id.isNotBlank()) add(EvrenModel(id))
            }
        }
    }

    suspend fun chat(
        model: String,
        messages: List<Pair<String, String>>,
        temperature: Double = 0.2
    ): String = withContext(Dispatchers.IO) {
        require(model.isNotBlank()) { "Select an EVREN model first." }
        val messageArray = JSONArray().apply {
            messages.forEach { (role, content) ->
                put(JSONObject().put("role", role).put("content", content))
            }
        }
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messageArray)
            .put("temperature", temperature)
            .put("stream", false)
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .header("Authorization", "Bearer ${requireKey()}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        val response = execute(request)
        response.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("EVREN returned an empty assistant response.")
    }

    private fun requireKey(): String =
        apiKey()?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("EVREN API key is missing.")

    private fun execute(request: Request): JSONObject {
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(raw).optString("error") }.getOrNull()
                throw IOException("EVREN HTTP ${response.code}: ${detail ?: raw.take(300)}")
            }
            return JSONObject(raw)
        }
    }

    companion object {
        const val BASE_URL = "https://evren-llmapi.ssyz.org.tr/v1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
