package com.example.engineeringagent.agent.reasoning

import com.example.engineeringagent.domain.EngineeringContext
import com.example.engineeringagent.domain.MatchConfidence
import org.springframework.stereotype.Component

/**
 * Renders an [EngineeringContext] as the text a model reads.
 *
 * Two constraints shape this. Prompt budget: a 3B local model handles a page of well-ordered facts
 * far better than ten pages of JSON. And data minimization: only what is needed to describe the
 * work is included, which is why file paths and counts appear but diff content does not unless
 * explicitly enabled.
 */
@Component
class ContextPromptRenderer {

    fun render(context: EngineeringContext): String = buildString {
        val issue = context.issue

        appendLine("# TICKET ${issue.key}")
        appendLine("Summary: ${issue.summary}")
        appendLine("Type: ${issue.issueType ?: "Unknown"}")
        appendLine("Jira status: ${issue.status}")
        issue.priority?.let { appendLine("Priority: $it") }
        appendLine()

        issue.description?.takeIf { it.isNotBlank() }?.let {
            appendLine("## Ticket description")
            appendLine(it.trim().take(DESCRIPTION_LIMIT))
            appendLine()
        }

        issue.acceptanceCriteria?.let {
            appendLine("## Acceptance criteria")
            appendLine(it.trim().take(DESCRIPTION_LIMIT))
            appendLine()
        }

        if (issue.comments.isNotEmpty()) {
            appendLine("## Recent ticket comments")
            issue.comments.takeLast(COMMENT_LIMIT).forEach { comment ->
                appendLine("- ${comment.author ?: "someone"}: ${comment.body.trim().take(COMMENT_CHAR_LIMIT)}")
            }
            appendLine()
        }

        if (issue.linkedIssues.isNotEmpty()) {
            appendLine("## Linked tickets")
            issue.linkedIssues.forEach {
                appendLine("- ${it.relationship} ${it.key} (${it.status ?: "unknown status"}): ${it.summary ?: ""}")
            }
            appendLine()
        }

        appendLine("# CODE ACTIVITY")
        val confirmed = context.activity.matches.filter { it.confidence == MatchConfidence.MATCHED }
        if (confirmed.isEmpty()) {
            appendLine("No pull requests were confirmed for this ticket.")
        } else {
            confirmed.forEach { match ->
                val pr = match.pullRequest
                appendLine(
                    "- ${pr.repository}#${pr.number} [${pr.state}]" +
                        (if (pr.draft) " (draft)" else "") + ": ${pr.title}",
                )
                pr.branch?.let { appendLine("    branch: $it") }
                if (match.reviews.isNotEmpty()) {
                    appendLine(
                        "    reviews: " + match.reviews.joinToString(", ") {
                            "${it.reviewer ?: "someone"} ${it.state}"
                        },
                    )
                }
            }
        }
        appendLine()

        val changes = context.codeChanges
        if (changes.pullRequestCount > 0) {
            appendLine("## Changes")
            appendLine(
                "${changes.commitCount} commit(s), ${changes.filesChanged} file(s) changed " +
                    "(+${changes.additions}/-${changes.deletions})",
            )
            if (changes.areas.isNotEmpty()) appendLine("Areas touched: ${changes.areas.joinToString(", ")}")
            if (changes.commitMessages.isNotEmpty()) {
                appendLine("Commit messages:")
                changes.commitMessages.forEach { appendLine("- $it") }
            }
            if (changes.files.isNotEmpty()) {
                appendLine("Files:")
                changes.files.take(FILE_LIMIT).forEach {
                    appendLine("- ${it.path} (${it.changeType}, +${it.additions}/-${it.deletions})")
                }
                if (changes.filesOmitted > 0) appendLine("- … and ${changes.filesOmitted} more")
            }
            appendLine()
        }

        val review = context.reviewState
        appendLine("## Review state")
        appendLine(
            "open=${review.openPullRequests} merged=${review.mergedPullRequests} " +
                "draft=${review.draftPullRequests} approvals=${review.approvals} " +
                "changesRequested=${review.changesRequested}",
        )
        if (review.awaitingReview) appendLine("At least one open pull request has no reviews yet.")
        appendLine()

        appendLine("# CONTEXT GAPS")
        if (context.gaps.isEmpty()) {
            appendLine("None. The evidence above is as complete as the sources allow.")
        } else {
            context.gaps.forEach { appendLine("- ${it.detail}") }
        }
    }

    private companion object {
        const val DESCRIPTION_LIMIT = 2_000
        const val COMMENT_LIMIT = 8
        const val COMMENT_CHAR_LIMIT = 400
        const val FILE_LIMIT = 25
    }
}
