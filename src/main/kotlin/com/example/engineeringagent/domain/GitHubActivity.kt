package com.example.engineeringagent.domain

import java.time.Instant

data class GitHubRepository(
    val owner: String,
    val name: String,
    val defaultBranch: String? = null,
    val url: String? = null,
) {
    val fullName: String get() = "$owner/$name"
}

data class GitHubPullRequest(
    val number: Int,
    val repository: String,
    val title: String,
    val body: String?,
    val state: PullRequestState,
    val draft: Boolean,
    val author: String?,
    val branch: String?,
    val baseBranch: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val mergedAt: Instant?,
    val url: String?,
    val additions: Int? = null,
    val deletions: Int? = null,
    val changedFiles: Int? = null,
)

enum class PullRequestState { OPEN, MERGED, CLOSED }

data class GitHubCommit(
    val sha: String,
    val message: String,
    val author: String?,
    val committedAt: Instant?,
    val url: String?,
)

data class GitHubFileChange(
    val path: String,
    val changeType: String,
    val additions: Int,
    val deletions: Int,
    /** Omitted unless diffs are explicitly enabled. See docs/SECURITY.md. */
    val patch: String? = null,
)

data class PullRequestReview(
    val reviewer: String?,
    val state: String,
    val submittedAt: Instant?,
    val body: String?,
)

/**
 * How confident we are that a pull request belongs to a Jira ticket.
 *
 * A wrong link produces a confidently wrong status report, so promotion to [MATCHED] requires an
 * explicit ticket reference — never circumstantial evidence. See ADR 0003.
 */
enum class MatchConfidence { MATCHED, POSSIBLE_MATCH, NO_MATCH }

/**
 * Why a pull request was linked to a ticket, ordered strongest first. Recorded so a summary can
 * qualify a weak link rather than presenting every match as equally certain.
 */
enum class MatchSignal {
    /** The Jira issue names the pull request URL outright. Not an inference. */
    EXPLICIT_PR_URL_IN_ISSUE,
    TICKET_KEY_IN_BRANCH,
    TICKET_KEY_IN_PR_TITLE,
    TICKET_KEY_IN_PR_BODY,
    TICKET_KEY_IN_COMMIT_MESSAGE,

    /** Found by a text search that could not then be confirmed against a specific field. */
    UNCONFIRMED_SEARCH_HIT,

    /**
     * Reached through a linked issue rather than this ticket. The pull request clearly belongs to
     * *some* ticket; that it is this ticket's work is the part that is unproven.
     */
    VIA_LINKED_ISSUE,
}

data class ActivityMatch(
    val pullRequest: GitHubPullRequest,
    val confidence: MatchConfidence,
    val signals: List<MatchSignal>,
    /**
     * The ticket key that produced the match. Not always the key we started from: work on one
     * ticket can land under another project's key.
     */
    val matchedVia: String,
    val commits: List<GitHubCommit> = emptyList(),
    val changedFiles: List<GitHubFileChange> = emptyList(),
    val reviews: List<PullRequestReview> = emptyList(),
)

/** Everything found in GitHub for one Jira ticket. */
data class TicketActivity(
    val ticketKey: String,
    val confidence: MatchConfidence,
    val matches: List<ActivityMatch>,
    /** Keys searched, including cross-project references discovered in the issue text. */
    val searchedKeys: List<String>,
    /** Populated when GitHub could not be reached, so callers can distinguish "none" from "unknown". */
    val unavailableReason: String? = null,
)
