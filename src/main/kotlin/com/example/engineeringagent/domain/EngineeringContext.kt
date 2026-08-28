package com.example.engineeringagent.domain

import java.time.Instant
import java.time.LocalDate

/**
 * Everything known about one ticket, assembled from trusted sources before any reasoning happens.
 *
 * This is the sole input to the AI layer (ADR 0002). Two properties matter more than completeness:
 *
 *  - Every fact is traceable to Jira or GitHub. Nothing here is inferred.
 *  - What is *missing* is stated explicitly in [gaps], rather than being silently absent. A model
 *    given a quietly incomplete picture will confidently fill the hole; one told "no reviews could
 *    be loaded" can say so.
 */
data class EngineeringContext(
    val ticketKey: String,
    val issue: JiraIssue,
    val activity: TicketActivity,
    val codeChanges: CodeChangeSummary,
    val reviewState: ReviewState,
    /** Named absences in the evidence. Empty means the picture is as complete as the sources allow. */
    val gaps: List<ContextGap> = emptyList(),
    val assembledAt: Instant,
)

/**
 * An aggregate view of what changed, deliberately kept at the level of paths and counts.
 *
 * Whole diffs are the most sensitive thing this system touches and the least necessary for
 * summarizing work, so patches appear only when explicitly enabled and are truncated even then.
 */
data class CodeChangeSummary(
    val pullRequestCount: Int,
    val commitCount: Int,
    val filesChanged: Int,
    val additions: Int,
    val deletions: Int,
    /** Files touched, capped; see [filesOmitted]. */
    val files: List<GitHubFileChange>,
    val filesOmitted: Int,
    /** Top-level areas touched, derived from paths — cheap orientation without reading the code. */
    val areas: List<String>,
    val commitMessages: List<String>,
    val diffsIncluded: Boolean,
)

/** Where the work stands in review, which is usually what a stand-up actually turns on. */
data class ReviewState(
    val openPullRequests: Int,
    val mergedPullRequests: Int,
    val draftPullRequests: Int,
    val approvals: Int,
    val changesRequested: Int,
    val awaitingReview: Boolean,
    val reviewers: List<String>,
)

/** A specific thing the evidence does not cover, and why. */
data class ContextGap(val kind: GapKind, val detail: String)

/**
 * Why a piece of evidence is missing.
 *
 * [operational] separates two things a reader conflates at their peril: a gap in the *work* (no
 * acceptance criteria were written, no code was found) from a gap in the *tooling* (this agent
 * could not reach GitHub, or was configured not to fetch diffs). Both belong in the notes a human
 * reads. Only the first may influence what the status says about the engineering, because
 * "the agent lacks a GitHub token" is not something a developer is blocked on.
 */
enum class GapKind(val operational: Boolean) {
    NO_GITHUB_ACTIVITY(operational = false),
    UNCERTAIN_CODE_LINK(operational = false),
    NO_ACCEPTANCE_CRITERIA(operational = false),
    NO_DESCRIPTION(operational = false),
    GITHUB_UNAVAILABLE(operational = true),
    DIFFS_EXCLUDED(operational = true),
    FILES_TRUNCATED(operational = true),
}

/** The day's contexts, one per active ticket. */
data class DailyWorkContext(
    val date: LocalDate,
    val user: String?,
    val contexts: List<EngineeringContext>,
    val gaps: List<ContextGap> = emptyList(),
)
