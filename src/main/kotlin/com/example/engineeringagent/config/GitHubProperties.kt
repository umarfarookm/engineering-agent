package com.example.engineeringagent.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "github")
data class GitHubProperties(
    val token: String = "",
    val org: String = "",
    val apiUrl: String = "https://api.github.com",
    /** Optional allow-list of owner/name. Empty means the whole org is in scope. */
    val repos: List<String> = emptyList(),
    /** Repositories whose contents must never be sent to an LLM. See docs/SECURITY.md. */
    val llmExcludedRepos: List<String> = emptyList(),
    /** Include file patches when building context. Off by default. */
    val includeDiffs: Boolean = false,
    val maxPullRequestsPerTicket: Int = 10,
) {
    fun isConfigured(): Boolean = token.isNotBlank() && org.isNotBlank()
}
