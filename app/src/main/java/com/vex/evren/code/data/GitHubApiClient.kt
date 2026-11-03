package com.vex.evren.code.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GitHubRepository(
    val id: Long,
    val fullName: String,
    val description: String?,
    val defaultBranch: String,
    val isPrivate: Boolean,
    val htmlUrl: String,
    val language: String?
)

class GitHubApiClient(
    private val token: () -> String?,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
) {
    suspend fun listAccessibleRepositories(): List<GitHubRepository> = withContext(Dispatchers.IO) {
        val accessToken = token()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("GitHub token is missing.")
        val repositories = mutableListOf<GitHubRepository>()
        var page = 1
        while (true) {
            val request = Request.Builder()
                .url("https://api.github.com/user/repos?per_page=100&page=$page&sort=updated&affiliation=owner,collaborator,organization_member")
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .get()
                .build()
            val response = http.newCall(request).execute()
            val items = response.use {
                val raw = it.body?.string().orEmpty()
                if (!it.isSuccessful) throw IOException("GitHub HTTP ${it.code}: ${raw.take(300)}")
                JSONArray(raw)
            }
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                repositories += GitHubRepository(
                    id = item.optLong("id"),
                    fullName = item.optString("full_name"),
                    description = item.optString("description").takeUnless { it == "null" || it.isBlank() },
                    defaultBranch = item.optString("default_branch", "main"),
                    isPrivate = item.optBoolean("private"),
                    htmlUrl = item.optString("html_url"),
                    language = item.optString("language").takeUnless { it == "null" || it.isBlank() }
                )
            }
            if (items.length() < 100) break
            page++
        }
        repositories
    }
}
