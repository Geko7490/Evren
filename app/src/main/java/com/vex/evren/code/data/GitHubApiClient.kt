package com.vex.evren.code.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class GitHubRepository(
    val id: Long, val fullName: String, val description: String?, val defaultBranch: String,
    val isPrivate: Boolean, val htmlUrl: String, val language: String?
)
data class GitHubUser(val login: String, val name: String?, val avatarUrl: String?)
data class GitHubTreeEntry(val path: String, val sha: String, val type: String, val size: Long)
data class GitHubFile(val path: String, val content: String, val sha: String, val size: Long)

class GitHubApiClient(
    private val token: () -> String?,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).build()
) {
    suspend fun validateToken(): GitHubUser = withContext(Dispatchers.IO) {
        val json = requestJson("GET", "/user")
        GitHubUser(json.optString("login"), json.optString("name").takeUnless { it == "null" },
            json.optString("avatar_url").takeUnless { it == "null" })
    }

    suspend fun listAccessibleRepositories(): List<GitHubRepository> = withContext(Dispatchers.IO) {
        val repositories = mutableListOf<GitHubRepository>()
        var page = 1
        while (true) {
            val items = requestArray("GET", "/user/repos?per_page=100&page=$page&sort=updated&affiliation=owner,collaborator,organization_member")
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                repositories += GitHubRepository(
                    item.optLong("id"), item.optString("full_name"),
                    item.optString("description").takeUnless { it == "null" || it.isBlank() },
                    item.optString("default_branch", "main"), item.optBoolean("private"),
                    item.optString("html_url"),
                    item.optString("language").takeUnless { it == "null" || it.isBlank() }
                )
            }
            if (items.length() < 100) break
            page++
        }
        repositories.distinctBy { it.id }
    }

    suspend fun getTree(fullName: String, branch: String): List<GitHubTreeEntry> = withContext(Dispatchers.IO) {
        val (owner, repo) = splitRepo(fullName)
        val ref = requestJson("GET", "/repos/$owner/$repo/git/ref/heads/${encodePath(branch)}")
        val commitSha = ref.optJSONObject("object")?.optString("sha").orEmpty()
        if (commitSha.isBlank()) throw IOException("Could not resolve branch '$branch'.")
        val commit = requestJson("GET", "/repos/$owner/$repo/git/commits/$commitSha")
        val treeSha = commit.optJSONObject("tree")?.optString("sha").orEmpty()
        if (treeSha.isBlank()) throw IOException("Could not resolve the Git tree for '$branch'.")
        val json = requestJson("GET", "/repos/$owner/$repo/git/trees/$treeSha?recursive=1")
        if (json.optBoolean("truncated")) throw IOException("GitHub truncated this repository tree. A narrower directory scan is required.")
        val tree = json.optJSONArray("tree") ?: JSONArray()
        buildList {
            for (i in 0 until tree.length()) {
                val item = tree.optJSONObject(i) ?: continue
                add(GitHubTreeEntry(item.optString("path"), item.optString("sha"), item.optString("type"), item.optLong("size")))
            }
        }
    }

    suspend fun readFile(fullName: String, path: String, ref: String): GitHubFile = withContext(Dispatchers.IO) {
        val (owner, repo) = splitRepo(fullName)
        val json = requestJson("GET", "/repos/$owner/$repo/contents/${encodePath(path)}?ref=${encodeQuery(ref)}")
        if (json.optString("type") != "file") throw IOException("Selected path is not a file.")
        val encoded = json.optString("content").replace("\n", "")
        val bytes = try { Base64.decode(encoded, Base64.DEFAULT) } catch (e: Exception) {
            throw IOException("Unsupported file encoding.", e)
        }
        GitHubFile(path, bytes.toString(Charsets.UTF_8), json.optString("sha"), json.optLong("size"))
    }

    suspend fun createBranch(fullName: String, branch: String, baseBranch: String): String = withContext(Dispatchers.IO) {
        val (owner, repo) = splitRepo(fullName)
        val base = requestJson("GET", "/repos/$owner/$repo/git/ref/heads/${encodePath(baseBranch)}")
        val sha = base.optJSONObject("object")?.optString("sha").orEmpty()
        if (sha.isBlank()) throw IOException("Could not resolve base branch.")
        requestJson("POST", "/repos/$owner/$repo/git/refs", JSONObject().put("ref", "refs/heads/$branch").put("sha", sha))
        sha
    }

    suspend fun updateFile(fullName: String, path: String, content: String, message: String, branch: String, existingSha: String?): String =
        withContext(Dispatchers.IO) {
            val (owner, repo) = splitRepo(fullName)
            val payload = JSONObject().put("message", message)
                .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                .put("branch", branch)
            if (!existingSha.isNullOrBlank()) payload.put("sha", existingSha)
            val result = requestJson("PUT", "/repos/$owner/$repo/contents/${encodePath(path)}", payload)
            result.optJSONObject("commit")?.optString("sha")?.takeIf { it.isNotBlank() }
                ?: throw IOException("GitHub did not return a commit SHA.")
        }

    suspend fun deleteFile(fullName: String, path: String, branch: String, existingSha: String): String =
        withContext(Dispatchers.IO) {
            val (owner, repo) = splitRepo(fullName)
            val payload = JSONObject().put("message", "agent: delete $path")
                .put("sha", existingSha).put("branch", branch)
            val result = requestJson("DELETE", "/repos/$owner/$repo/contents/${encodePath(path)}", payload)
            result.optJSONObject("commit")?.optString("sha")?.takeIf { it.isNotBlank() }
                ?: throw IOException("GitHub did not return a deletion commit SHA.")
        }

    private fun requestJson(method: String, path: String, payload: JSONObject? = null): JSONObject {
        val raw = requestRaw(method, path, payload)
        return try { JSONObject(raw) } catch (e: Exception) { throw IOException("Invalid GitHub JSON response.", e) }
    }
    private fun requestArray(method: String, path: String): JSONArray {
        val raw = requestRaw(method, path, null)
        return try { JSONArray(raw) } catch (e: Exception) { throw IOException("Invalid GitHub list response.", e) }
    }
    private fun requestRaw(method: String, path: String, payload: JSONObject?): String {
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
                val message = runCatching { JSONObject(body).optString("message") }.getOrNull()
                throw IOException("GitHub HTTP ${response.code}: ${message ?: body.take(240)}")
            }
            return body
        }
    }
    private fun splitRepo(fullName: String): Pair<String, String> {
        val parts = fullName.split("/")
        require(parts.size == 2 && parts.all { it.isNotBlank() }) { "Invalid repository name." }
        return parts[0] to parts[1]
    }
    private fun encodePath(value: String) = value.split("/").joinToString("/") { encodeQuery(it) }
    private fun encodeQuery(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    companion object { private val JSON = "application/json; charset=utf-8".toMediaType() }
}
