package com.example.engineeringagent.agent.reasoning

import com.example.engineeringagent.domain.DailyWorkSummary
import com.example.engineeringagent.domain.EngineeringContext
import com.example.engineeringagent.domain.MatchConfidence
import com.example.engineeringagent.domain.PullRequestState
import com.example.engineeringagent.domain.StatusConsistency
import com.example.engineeringagent.domain.SummarySource
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * Builds a summary from the evidence alone, with no model involved.
 *
 * This runs when AI is switched off and when the model cannot be trusted, so it is not a toy: a
 * factual, slightly mechanical account of the work is more useful than an eloquent invented one,
 * and it is always available.
 */
@Component
class DeterministicSummarizer(private val clock: Clock = Clock.systemUTC()) {

    fun summarize(context: EngineeringContext): DailyWorkSummary {
        val confirmed = context.activity.matches.filter { it.confidence == MatchConfidence.MATCHED }
        val merged = confirmed.filter { it.pullRequest.state == PullRequestState.MERGED }
        val open = confirmed.filter { it.pullRequest.state == PullRequestState.OPEN }
        val changes = context.codeChanges
        // "GitHub said there is nothing" and "GitHub was never asked" look identical once the
        // matches list is empty. Only the first justifies telling the developer to go link a PR.
        val githubUnknown = context.activity.unavailableReason != null

        val completed = merged.map { "Merged ${it.pullRequest.repository}#${it.pullRequest.number}: ${it.pullRequest.title}" }

        val inProgress = open.map { match ->
            val pr = match.pullRequest
            val state = when {
                pr.draft -> "draft"
                match.reviews.any { it.state == "CHANGES_REQUESTED" } -> "changes requested"
                match.reviews.any { it.state == "APPROVED" } -> "approved, not yet merged"
                else -> "awaiting review"
            }
            "${pr.repository}#${pr.number} ($state): ${pr.title}"
        }

        val nextSteps = buildList {
            // Only work that is still open can have outstanding review comments. A merged pull
            // request carries its review history forever, and counting that as an action item
            // tells the developer to redo something they already finished.
            if (open.any { match -> match.reviews.any { it.state == "CHANGES_REQUESTED" } }) {
                add("Address review comments.")
            }
            if (context.reviewState.awaitingReview) add("Follow up on the pull request awaiting review.")
            if (confirmed.isEmpty() && !githubUnknown) add("Link the work to this ticket, or start it.")
        }

        return DailyWorkSummary(
            ticketKey = context.ticketKey,
            summary = describe(context, confirmed.size, changes.commitCount, changes.filesChanged, githubUnknown),
            completed = completed,
            inProgress = inProgress,
            remaining = emptyList(),
            blockers = emptyList(),
            nextSteps = nextSteps,
            // Judging whether a status matches the work needs interpretation, which is exactly what
            // this path lacks. Claiming otherwise would be the mistake it exists to avoid.
            statusConsistency = StatusConsistency.UNKNOWN,
            confidence = if (confirmed.isEmpty()) 0.1 else 0.5,
            notes = context.gaps.map { it.detail },
            generatedBy = SummarySource.DETERMINISTIC,
            generatedAt = Instant.now(clock),
        )
    }

    /**
     * The ticket key is omitted deliberately: every renderer of a summary already leads with it,
     * and repeating it here produces headings like "*ENG-267* — ENG-267 (In Progress): …".
     */
    private fun describe(
        context: EngineeringContext,
        pullRequests: Int,
        commits: Int,
        files: Int,
        githubUnknown: Boolean,
    ): String {
        val subject = "${context.issue.summary.trimEnd('.')} (${context.issue.status})."
        return if (githubUnknown) {
            "$subject Code activity could not be checked, so this covers the ticket only."
        } else if (pullRequests == 0) {
            "$subject No code activity was found for this ticket."
        } else {
            "$subject $pullRequests pull request(s), $commits commit(s), $files file(s) changed."
        }
    }
}
