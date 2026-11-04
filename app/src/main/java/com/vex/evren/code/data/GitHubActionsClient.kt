package com.vex.evren.code.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class ActionsRun(
    val id: Long, val name: String, val branch: String, val status: String,
    val conclusion: String?, val runNumber: Int, val htmlUrl: String, val headSha: String
)
data class ActionsArtifact(
    val id: Long, val name: String, val sizeBytes: Long, val expired: Boolean,
    val expiresAt: String?, val downloadUrl: String
)

/** GitHub-hosted build orchestration. No Gradle/Android SDK is required on the phone. */
class GitHubActionsClient(
    private val token: () -> String?,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).build()
) {
    suspend fun listWorkflows(repository: String): List<JSONObject> = io {
        val (owner, repo) = splitRepo(repository)
        val items = json("GET", "/repos/$owner/$repo/actions/workflows").optJSONArray("workflows") ?: JSONArray()
        (0 until items.length()).mapNotNull { items.optJSONObject(it) }
    }

    suspend fun listRuns(repository: String, branch: String? = null, limit: Int = 20): List<ActionsRun> = io {
        val (owner, repo) = splitRepo(repository)
        val query = buildString {
            append("?per_page=${limit.coerceIn(1, 100)}")
            if (!branch.isNullOrBlank()) append("&branch=${enc(branch)}")
        }
        val items = json("GET", "/repos/$owner/$repo/actions/runs$query").optJSONArray("workflow_runs") ?: JSONArray()
        (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.let { r -> ActionsRun(
                r.optLong("id"), r.optString("name"), r.optString("head_branch"),
                r.optString("status"), r.optString("conclusion").takeUnless { it == "null" || it.isBlank() },
                r.optInt("run_number"), r.optString("html_url"), r.optString("head_sha")
            ) }
        }
    }

    suspend fun dispatch(repository: String, workflowIdOrFile: String, branch: String, inputs: JSONObject = JSONObject()) = io {
        val (owner, repo) = splitRepo(repository)
        val payload = JSONObject().put("ref", branch).put("inputs", inputs)
        request("POST", "/repos/$owner/$repo/actions/workflows/${enc(workflowIdOrFile)}/dispatches", payload)
        Unit
    }

    suspend fun jobs(repository: String, runId: Long): List<JSONObject> = io {
        val (owner, repo) = splitRepo(repository)
        val items = json("GET", "/repos/$owner/$repo/actions/runs/$runId/jobs?per_page=100").optJSONArray("jobs") ?: JSONArray()
        (0 until items.length()).mapNotNull { items.optJSONObject(it) }
    }

    suspend fun jobLogs(repository: String, jobId: Long): String = io {
        val (owner, repo) = splitRepo(repository)
        request("GET", "/repos/$owner/$repo/actions/jobs/$jobId/logs", null)
    }

    suspend fun artifacts(repository: String, runId: Long): List<ActionsArtifact> = io {
        val (owner, repo) = splitRepo(repository)
        val items = json("GET", "/repos/$owner/$repo/actions/runs/$runId/artifacts?per_page=100").optJSONArray("artifacts") ?: JSONArray()
        (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.let { a ->
            ActionsArtifact(a.optLong("id"), a.optString("name"), a.optLong("size_in_bytes"),
                a.optBoolean("expired"), a.optString("expires_at").takeUnless { it == "null" }, a.optString("archive_download_url"))
        } }
    }

    suspend fun downloadArtifact(repository: String, artifactId: Long, destination: File): Pair<Long, String> = io {
        val (owner, repo) = splitRepo(repository)
        val accessToken = token()?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("GitHub token is missing.")
        destination.parentFile?.mkdirs()
        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/actions/artifacts/$artifactId/zip")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get().build()
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val temporary = File(destination.parentFile, destination.name + ".part")
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Artifact download HTTP ${response.code}: ${response.message}")
                val body = response.body ?: throw IOException("GitHub returned an empty artifact.")
                val declared = body.contentLength()
                if (declared > MAX_ARTIFACT_BYTES) throw IOException("Artifact exceeds the 250 MB download limit.")
                body.byteStream().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_ARTIFACT_BYTES) throw IOException("Artifact exceeds the 250 MB download limit.")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            if (!temporary.renameTo(destination)) throw IOException("Downloaded artifact could not be finalized.")
            total to digest.digest().joinToString("") { "%02x".format(it) }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    private fun json(method: String, path: String, payload: JSONObject? = null) =
        try { JSONObject(request(method, path, payload)) } catch (e: Exception) { throw IOException("GitHub returned invalid JSON.", e) }

    private fun request(method: String, path: String, payload: JSONObject?): String {
        val accessToken = token()?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("GitHub token is missing.")
        val builder = Request.Builder().url("https://api.github.com$path")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        if (payload != null) builder.method(method, payload.toString().toRequestBody(JSON))
        else builder.method(method, null)
        http.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(body).optString("message") }.getOrNull()
                throw IOException("GitHub Actions HTTP ${response.code}: ${detail ?: body.take(300)}")
            }
            return body
        }
    }

    private fun splitRepo(value: String): Pair<String, String> {
        val parts = value.split("/")
        require(parts.size == 2 && parts.all { it.isNotBlank() }) { "Repository must use owner/name format." }
        return parts[0] to parts[1]
    }
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    companion object { private const val MAX_ARTIFACT_BYTES = 250L * 1024L * 1024L; private val JSON = "application/json; charset=utf-8".toMediaType() }
}
