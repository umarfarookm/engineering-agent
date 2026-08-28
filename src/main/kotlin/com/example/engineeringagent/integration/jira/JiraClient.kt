package com.example.engineeringagent.integration.jira

import com.example.engineeringagent.config.JiraProperties
import com.example.engineeringagent.domain.JiraComment
import com.example.engineeringagent.domain.JiraIssue
import com.example.engineeringagent.domain.JiraUser
import com.example.engineeringagent.exception.IssueNotFoundException
import com.example.engineeringagent.exception.JiraNotConfiguredException
import com.example.engineeringagent.exception.JiraUnavailableException
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.util.UriBuilder

/**
 * Read-only client for Jira Cloud REST API v3.
 *
 * Two API details drive the shape of this class:
 *
 *  1. `GET /rest/api/3/search` was removed and now returns 410 Gone. Searching goes through
 *     `/rest/api/3/search/jql`, which paginates with an opaque `nextPageToken` cursor and reports
 *     no total count.
 *  2. That endpoint returns **no issue fields by default** — every field must be requested
 *     explicitly. Omitting `fields` yields issues containing only ids and keys, which looks like
 *     data loss rather than an error.
 */
@Component
class JiraClient(
    private val jiraRestClient: RestClient,
    private val properties: JiraProperties,
    private val mapper: JiraIssueMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val issueFields: List<String>
        get() = buildList {
            addAll(
                listOf(
                    "summary", "description", "status", "assignee", "priority",
                    "labels", "issuetype", "created", "updated", "issuelinks",
                ),
            )
            properties.acceptanceCriteriaField?.let { add(it) }
        }

    /** The authenticated account. Used so the JQL never hard-codes a username. */
    fun getCurrentUser(): JiraUser {
        val node = get("/rest/api/3/myself")
        return JiraUser(
            accountId = node.path("accountId").textValue(),
            displayName = node.path("displayName").textValue(),
            email = node.path("emailAddress").textValue(),
        )
    }

    /**
     * Issues assigned to the authenticated user in any configured active status,
     * most recently updated first.
     */
    fun getInProgressIssues(): List<JiraIssue> {
        val statuses = properties.inProgressStatuses
            .filter { it.isNotBlank() }
            .joinToString(", ") { "\"${it.replace("\"", "\\\"")}\"" }

        require(statuses.isNotBlank()) { "jira.in-progress-statuses must not be empty" }

        val jql = "assignee = currentUser() AND status IN ($statuses) ORDER BY updated DESC"
        return search(jql)
    }

    /** Executes a JQL search, following `nextPageToken` cursors until exhausted. */
    fun search(jql: String): List<JiraIssue> {
        val issues = mutableListOf<JiraIssue>()
        var pageToken: String? = null
        var pages = 0

        do {
            val currentToken = pageToken
            val response = get("/rest/api/3/search/jql") { builder ->
                builder.queryParam("jql", jql)
                    .queryParam("maxResults", properties.maxResults)
                    .queryParam("fields", issueFields.joinToString(","))
                    .apply { currentToken?.let { queryParam("nextPageToken", it) } }
            }

            response.path("issues").forEach { issues.add(mapper.toDomain(it, properties)) }
            pageToken = response.path("nextPageToken").textValue()
            pages++
        } while (pageToken != null && pages < MAX_PAGES)

        if (pageToken != null) {
            log.warn("Stopped paginating JQL search after {} pages; results may be truncated", MAX_PAGES)
        }
        log.info("JQL search returned {} issue(s) across {} page(s)", issues.size, pages)
        return issues
    }

    fun getIssue(issueKey: String): JiraIssue {
        val node = get("/rest/api/3/issue/$issueKey") { builder ->
            builder.queryParam("fields", issueFields.joinToString(","))
        }
        return mapper.toDomain(node, properties)
    }

    fun getIssueComments(issueKey: String): List<JiraComment> {
        val node = get("/rest/api/3/issue/$issueKey/comment")
        return node.path("comments").map(mapper::toComment)
    }

    /** Linked issues are returned inline with the issue, so this is a convenience view. */
    fun getLinkedIssues(issueKey: String) = getIssue(issueKey).linkedIssues

    private fun get(path: String, query: (UriBuilder) -> Unit = {}): JsonNode {
        if (!properties.isConfigured()) {
            throw JiraNotConfiguredException(
                "Jira is not configured. Set JIRA_BASE_URL, JIRA_EMAIL and JIRA_API_TOKEN.",
            )
        }
        return try {
            jiraRestClient.get()
                .uri { builder -> builder.path(path).also(query).build() }
                .retrieve()
                .onStatus({ status -> status.value() == 404 }) { _, _ ->
                    throw IssueNotFoundException(path)
                }
                .onStatus(HttpStatusCode::isError) { _, response ->
                    // The body may echo request detail; the status alone is what we surface.
                    throw JiraUnavailableException(
                        "Jira returned ${response.statusCode} for $path",
                    )
                }
                .body(JsonNode::class.java)
                ?: throw JiraUnavailableException("Jira returned an empty body for $path")
        } catch (e: RestClientException) {
            throw JiraUnavailableException("Could not reach Jira for $path", e)
        }
    }

    private companion object {
        /** Guards against the documented cases of `nextPageToken` cursors that never terminate. */
        const val MAX_PAGES = 20
    }
}
