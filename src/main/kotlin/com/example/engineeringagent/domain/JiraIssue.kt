package com.example.engineeringagent.domain

import java.time.Instant

/**
 * Normalized internal representation of a Jira issue.
 *
 * Deliberately not a mirror of the Jira REST payload: ADF documents are flattened to plain text,
 * absent fields become null rather than empty strings, and nothing vendor-specific leaks past the
 * mapper. Fields the API did not supply are null and stay null — they are never guessed.
 */
data class JiraIssue(
    val id: String,
    val key: String,
    val summary: String,
    val description: String?,
    val status: String,
    val statusCategory: String?,
    val assignee: JiraUser?,
    val priority: String?,
    val labels: List<String> = emptyList(),
    val issueType: String?,
    val comments: List<JiraComment> = emptyList(),
    val acceptanceCriteria: String?,
    val linkedIssues: List<LinkedIssue> = emptyList(),
    val created: Instant?,
    val updated: Instant?,
    val url: String?,
)

data class JiraUser(
    val accountId: String?,
    val displayName: String?,
    val email: String?,
)

data class JiraComment(
    val id: String,
    val author: String?,
    val body: String,
    val created: Instant?,
    val updated: Instant?,
)

data class LinkedIssue(
    val key: String,
    val summary: String?,
    val status: String?,
    /** e.g. "blocks", "is blocked by", "relates to" — the direction as it applies to this issue. */
    val relationship: String,
)
