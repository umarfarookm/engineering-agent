package com.example.engineeringagent.integration.github

import com.example.engineeringagent.config.GitHubProperties
import com.example.engineeringagent.domain.GitHubCommit
import com.example.engineeringagent.domain.GitHubFileChange
import com.example.engineeringagent.domain.GitHubPullRequest
import com.example.engineeringagent.domain.PullRequestReview
import com.example.engineeringagent.domain.PullRequestState
import com.example.engineeringagent.exception.GitHubNotConfiguredException
import com.example.engineeringagent.exception.GitHubUnavailableException
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.util.UriBuilder
import java.time.Instant

/**
 * Read-only client for the GitHub REST API.
 *
 * The search API is rate-limited far more tightly than the core API (tens of requests per minute
 * rather than thousands), so searches are issued once per ticket key and their results reused,
 * never once per repository.
 */
@Component
class GitHubClient(
    private val gitHubRestClient: RestClient,
    private val properties: GitHubProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Pull requests mentioning [key] anywhere GitHub indexes (title, body, comments).
     *
     * Note that GitHub does not index branch names, so a pull request whose only reference to the
     * ticket is its branch will not appear here. Branch evidence is confirmed after the fact, in
     * [toPullRequest], from the PR's own `head.ref`.
     */
    fun searchPullRequests(key: String): List<GitHubPullRequest> {
        val scope = if (properties.repos.isNotEmpty()) {
            properties.repos.joinToString(" ") { "repo:$it" }
        } else {
            "org:${properties.org}"
        }

        val response = get("/search/issues") { builder ->
            builder.queryParam("q", "$scope type:pr $key")
                // GitHub's advanced issue search; passing it explicitly avoids depending on
                // whatever the default happens to be.
                .queryParam("advanced_search", "true")
                .queryParam("per_page", properties.maxPullRequestsPerTicket)
                .queryParam("sort", "updated")
                .queryParam("order", "desc")
        }

        return response.path("items").mapNotNull { item ->
            // Search results are issue-shaped and carry no branch or merge detail; fetch the real
            // pull request so matching can inspect head.ref.
            val prUrl = item.path("pull_request").path("url").textValue() ?: return@mapNotNull null
            val (owner, repo, number) = parsePullRequestApiUrl(prUrl) ?: return@mapNotNull null
            runCatching { getPullRequest(owner, repo, number) }
                .onFailure { log.warn("Could not load {}/{}#{}: {}", owner, repo, number, it.message) }
                .getOrNull()
        }
    }

    fun getPullRequest(owner: String, repo: String, number: Int): GitHubPullRequest =
        toPullRequest(get("/repos/$owner/$repo/pulls/$number"))

    fun getPullRequestCommits(owner: String, repo: String, number: Int): List<GitHubCommit> =
        get("/repos/$owner/$repo/pulls/$number/commits") { it.queryParam("per_page", 100) }
            .map { node ->
                GitHubCommit(
                    sha = node.path("sha").asText(""),
                    message = node.path("commit").path("message").asText(""),
                    author = node.path("commit").path("author").path("name").textValue()
                        ?: node.path("author").path("login").textValue(),
                    committedAt = parseInstant(node.path("commit").path("author").path("date").textValue()),
                    url = node.path("html_url").textValue(),
                )
            }

    /**
     * Changed files. Patch content is included only when explicitly enabled, because it is the
     * proprietary source code this system is otherwise careful not to move around.
     */
    fun getChangedFiles(owner: String, repo: String, number: Int): List<GitHubFileChange> =
        get("/repos/$owner/$repo/pulls/$number/files") { it.queryParam("per_page", 100) }
            .map { node ->
                GitHubFileChange(
                    path = node.path("filename").asText(""),
                    changeType = node.path("status").asText("modified"),
                    additions = node.path("additions").asInt(0),
                    deletions = node.path("deletions").asInt(0),
                    patch = if (properties.includeDiffs) node.path("patch").textValue() else null,
                )
            }

    fun getReviews(owner: String, repo: String, number: Int): List<PullRequestReview> =
        get("/repos/$owner/$repo/pulls/$number/reviews") { it.queryParam("per_page", 100) }
            .map { node ->
                PullRequestReview(
                    reviewer = node.path("user").path("login").textValue(),
                    state = node.path("state").asText("COMMENTED"),
                    submittedAt = parseInstant(node.path("submitted_at").textValue()),
                    body = node.path("body").textValue()?.ifBlank { null },
                )
            }

    private fun toPullRequest(node: JsonNode): GitHubPullRequest {
        val merged = node.path("merged_at").textValue()
        return GitHubPullRequest(
            number = node.path("number").asInt(),
            repository = node.path("base").path("repo").path("full_name").asText(""),
            title = node.path("title").asText(""),
            body = node.path("body").textValue()?.ifBlank { null },
            state = when {
                merged != null -> PullRequestState.MERGED
                node.path("state").asText() == "closed" -> PullRequestState.CLOSED
                else -> PullRequestState.OPEN
            },
            draft = node.path("draft").asBoolean(false),
            author = node.path("user").path("login").textValue(),
            branch = node.path("head").path("ref").textValue(),
            baseBranch = node.path("base").path("ref").textValue(),
            createdAt = parseInstant(node.path("created_at").textValue()),
            updatedAt = parseInstant(node.path("updated_at").textValue()),
            mergedAt = parseInstant(merged),
            url = node.path("html_url").textValue(),
            additions = node.path("additions").takeIf { it.isNumber }?.asInt(),
            deletions = node.path("deletions").takeIf { it.isNumber }?.asInt(),
            changedFiles = node.path("changed_files").takeIf { it.isNumber }?.asInt(),
        )
    }

    private fun parsePullRequestApiUrl(url: String): Triple<String, String, Int>? {
        val match = API_PR_URL.find(url) ?: return null
        return Triple(match.groupValues[1], match.groupValues[2], match.groupValues[3].toInt())
    }

    private fun parseInstant(value: String?): Instant? =
        value?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun get(path: String, query: (UriBuilder) -> Unit = {}): JsonNode {
        if (!properties.isConfigured()) {
            throw GitHubNotConfiguredException(
                "GitHub is not configured. Set GITHUB_TOKEN and GITHUB_ORG.",
            )
        }
        return try {
            gitHubRestClient.get()
                .uri { builder -> builder.path(path).also(query).build() }
                .retrieve()
                .onStatus(HttpStatusCode::isError) { _, response ->
                    val remaining = response.headers.getFirst("x-ratelimit-remaining")
                    if (response.statusCode.value() == 403 && remaining == "0") {
                        throw GitHubUnavailableException("GitHub rate limit exhausted for $path")
                    }
                    // Response bodies can echo the query; only the status is surfaced.
                    throw GitHubUnavailableException("GitHub returned ${response.statusCode} for $path")
                }
                .body(JsonNode::class.java)
                ?: throw GitHubUnavailableException("GitHub returned an empty body for $path")
        } catch (e: RestClientException) {
            throw GitHubUnavailableException("Could not reach GitHub for $path", e)
        }
    }

    private companion object {
        val API_PR_URL = Regex("""/repos/([\w.-]+)/([\w.-]+)/pulls/(\d+)""")
    }
}
