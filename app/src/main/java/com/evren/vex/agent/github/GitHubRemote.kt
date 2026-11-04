package com.evren.vex.agent.github

/**
 * Thin abstraction over the GitHub REST API.
 *
 * Implementations perform the actual network calls; they must never be called
 * directly by agent code. [GitHubPushService] is the only sanctioned entry point
 * because it enforces the [com.evren.vex.agent.policy.PushMode] policy.
 */
interface GitHubRemote {

    suspend fun pushCommit(
        repository: String,
        branch: String,
        message: String,
        files: Map<String, String>
    ): Result<String>

    suspend fun createRepository(
        name: String,
        isPrivate: Boolean,
        description: String?
    ): Result<String>

    suspend fun openPullRequest(
        repository: String,
        head: String,
        base: String,
        title: String,
        body: String
    ): Result<String>
}
