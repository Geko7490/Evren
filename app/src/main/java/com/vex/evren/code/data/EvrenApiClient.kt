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

data class EvrenModelDetails(
    val id: String, val contextLength: Int?, val defaultMaxOutputTokens: Int?,
    val tools: Boolean?, val thinking: Boolean?, val vision: Boolean?, val precision: String?
) {
    fun recommendedAgentBudget(): Int =
        defaultMaxOutputTokens?.let { minOf(4096, it).coerceAtLeast(1) } ?: 4096
}

class EvrenApiClient(
    private val apiKey: () -> String?,
    private val baseUrl: () -> String = { DEFAULT_BASE_URL },
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS).build()
) {
    suspend fun validateConnection(): List<EvrenModel> = listModels()

    suspend fun getTermsStatus(): JSONObject = withContext(Dispatchers.IO) {
        execute(authenticatedGet("/terms/status"))
    }

    suspend fun getTermsText(): Pair<Int, String> = withContext(Dispatchers.IO) {
        val response = execute(authenticatedGet("/terms/text"))
        val version = response.optInt("version", -1)
        val content = response.optString("content")
        if (version < 0 || content.isBlank()) throw IOException("EVREN terms response was incomplete.")
        version to content
    }

    suspend fun acceptTerms(version: Int): JSONObject = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("version", version)
        execute(Request.Builder().url("${normalizedBaseUrl()}/terms/accept")
            .header("Authorization", "Bearer ${requireKey()}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE)).build())
    }

    suspend fun getModelDetails(model: String): EvrenModelDetails = withContext(Dispatchers.IO) {
        require(model.isNotBlank() && model != "auto") { "Automatic routing has no fixed model card." }
        val encoded = java.net.URLEncoder.encode(model, "UTF-8").replace("+", "%20")
        val response = execute(authenticatedGet("/models/$encoded"))
        val capabilities = response.optJSONObject("capabilities")
        EvrenModelDetails(
            id = response.optString("id", model),
            contextLength = response.optIntOrNull("context_length"),
            defaultMaxOutputTokens = response.optIntOrNull("default_max_output_tokens"),
            tools = capabilities?.optBooleanOrNull("tools"),
            thinking = capabilities?.optBooleanOrNull("thinking"),
            vision = capabilities?.optBooleanOrNull("vision"),
            precision = response.optString("precision").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    private fun authenticatedGet(path: String): Request = Request.Builder()
        .url("${normalizedBaseUrl()}$path")
        .header("Authorization", "Bearer ${requireKey()}")
        .header("Accept", "application/json")
        .get().build()

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key).takeIf { it > 0 } else null

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null

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

    suspend fun chatWithTools(
        model: String, tools: JSONArray, messages: JSONArray, maxTokens: Int? = 4096
    ): JSONObject = withContext(Dispatchers.IO) {
        val safeMaxTokens = maxTokens?.coerceAtLeast(1)
        val payload = JSONObject().put("model", model).put("messages", messages)
            .put("temperature", 0.1)
            .put("tools", tools).put("tool_choice", "auto").put("stream", false)
        safeMaxTokens?.let { payload.put("max_tokens", it) }
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
                    "list_files(repository, path optional), read_file(repository, path), " +
                    "propose_file_change(repository, path, complete content), finish_task(summary). " +
                    "The repository argument must be the exact owner/repo name from the connected repository list. " +
                    "Do not wrap JSON in markdown.")
            }
            val fallback = postChat(JSONObject().put("model", model).put("messages", fallbackMessages)
                .put("temperature", 0.1).put("stream", false)
                .apply { safeMaxTokens?.let { put("max_tokens", it) } })
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
        const val DEFAULT_BASE_URL = "https://evren-llmapi.ssyz.org.tr/v1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
