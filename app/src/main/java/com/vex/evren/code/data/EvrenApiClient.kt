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
    private val baseUrl: () -> String = { DEFAULT_BASE_URL },
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS).build()
) {
    suspend fun validateConnection(): List<EvrenModel> = listModels()

    suspend fun listModels(): List<EvrenModel> = withContext(Dispatchers.IO) {
        val json = execute(Request.Builder().url("${normalizedBaseUrl()}/models")
            .header("Authorization", "Bearer ${requireKey()}").header("Accept", "application/json").get().build())
        val data = json.optJSONArray("data") ?: json.optJSONArray("models") ?: JSONArray()
        val models = buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index)
                val id = item?.optString("id").orEmpty().ifBlank { item?.optString("name").orEmpty() }
                if (id.isNotBlank()) add(EvrenModel(id, item?.optString("display_name")
                    ?.takeIf { it.isNotBlank() && it != "null" } ?: id))
            }
        }
        if (models.isEmpty()) throw IOException("EVREN responded, but no model IDs were returned.")
        models
    }

    suspend fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double = 0.2): String =
        withContext(Dispatchers.IO) {
            val messageArray = JSONArray().apply {
                messages.forEach { (role, content) -> put(JSONObject().put("role", role).put("content", content)) }
            }
            val response = postChat(JSONObject().put("model", model).put("messages", messageArray)
                .put("temperature", temperature).put("stream", false))
            response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?.optString("content")?.takeIf { it.isNotBlank() }
                ?: throw IOException("EVREN returned an empty assistant response.")
        }

    suspend fun chatWithTools(model: String, tools: JSONArray, messages: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("model", model).put("messages", messages)
            .put("temperature", 0.1).put("tools", tools).put("tool_choice", "auto")
        try {
            val response = postChat(payload)
            response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw IOException("EVREN response did not contain an assistant message.")
        } catch (error: IOException) {
            val message = error.message.orEmpty()
            if (!message.contains("HTTP 400") && !message.contains("HTTP 404") && !message.contains("HTTP 422")) throw error
            val fallbackMessages = JSONArray()
            for (index in 0 until messages.length()) {
                val item = messages.optJSONObject(index) ?: continue
                fallbackMessages.put(JSONObject(item.toString()))
            }
            fallbackMessages.optJSONObject(0)?.let { system ->
                system.put("content", system.optString("content") +
                    "\n\nNative function calling is unavailable. Return exactly one JSON object per response: " +
                    "{\"tool\":\"tool_name\",\"arguments\":{...}} to call a tool, or " +
                    "{\"final\":\"answer text\"} to finish. Available tools: submit_plan(steps array), " +
                    "list_files(path optional), read_file(path), propose_file_change(path, complete content), " +
                    "finish_task(summary). Do not wrap JSON in markdown.")
            }
            val fallback = postChat(JSONObject().put("model", model).put("messages", fallbackMessages)
                .put("temperature", 0.1).put("stream", false))
            fallback.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw IOException("EVREN fallback response did not contain an assistant message.")
        }
    }

    private fun postChat(payload: JSONObject): JSONObject {
        require(payload.optString("model").isNotBlank()) { "Select an EVREN model first." }
        return execute(Request.Builder().url("${normalizedBaseUrl()}/chat/completions")
            .header("Authorization", "Bearer ${requireKey()}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE)).build())
    }

    private fun normalizedBaseUrl(): String = baseUrl().trim().trimEnd('/').let {
        require(it.startsWith("https://")) { "EVREN API URL must use HTTPS." }
        if (it.endsWith("/v1")) it else "$it/v1"
    }
    private fun requireKey() = apiKey()?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("EVREN API key is missing.")
    private fun execute(request: Request): JSONObject {
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    JSONObject(raw).optString("message").ifBlank { JSONObject(raw).optString("error") }
                }.getOrNull()
                throw IOException("EVREN HTTP ${response.code}: ${detail ?: raw.take(300)}")
            }
            return JSONObject(raw)
        }
    }
    companion object {
        const val DEFAULT_BASE_URL = "https://evren.ssyz.org.tr/v1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
