package com.example.engineeringagent.agent.context

import com.example.engineeringagent.config.ContextProperties
import com.example.engineeringagent.config.GitHubProperties
import com.example.engineeringagent.domain.ActivityMatch
import com.example.engineeringagent.domain.CodeChangeSummary
import com.example.engineeringagent.domain.ContextGap
import com.example.engineeringagent.domain.EngineeringContext
import com.example.engineeringagent.domain.GapKind
import com.example.engineeringagent.domain.GitHubFileChange
import com.example.engineeringagent.domain.JiraIssue
import com.example.engineeringagent.domain.MatchConfidence
import com.example.engineeringagent.domain.PullRequestState
import com.example.engineeringagent.domain.ReviewState
import com.example.engineeringagent.domain.TicketActivity
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * Combines a Jira issue with its GitHub activity into the object the AI layer reasons over.
 *
 * The assembler does no reasoning of its own. It gathers, normalizes, bounds, and — importantly —
 * records what it could not gather.
 */
@Component
class EngineeringContextAssembler(
    private val gitHubProperties: GitHubProperties,
    private val contextProperties: ContextProperties,
    private val clock: Clock = Clock.systemUTC(),
) {

    fun assemble(issue: JiraIssue, activity: TicketActivity): EngineeringContext {
        // Only confirmed matches contribute evidence. A possible match is reported as a gap
        // instead, so the reasoning layer can mention it without treating it as fact.
        val confirmed = activity.matches.filter { it.confidence == MatchConfidence.MATCHED }

        return EngineeringContext(
            ticketKey = issue.key,
            issue = issue,
            activity = activity,
            codeChanges = summarizeChanges(confirmed),
            reviewState = reviewState(confirmed),
            gaps = gaps(issue, activity, confirmed),
            assembledAt = Instant.now(clock),
        )
    }

    private fun summarizeChanges(matches: List<ActivityMatch>): CodeChangeSummary {
        val allFiles = matches.flatMap { it.changedFiles }
        val kept = allFiles.take(contextProperties.maxFilesPerTicket).map(::boundPatch)
        val commitMessages = matches.flatMap { it.commits }
            .map { it.message.lines().first().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(contextProperties.maxCommitMessages)

        return CodeChangeSummary(
            pullRequestCount = matches.size,
            commitCount = matches.sumOf { it.commits.size },
            filesChanged = allFiles.size,
            additions = allFiles.sumOf { it.additions },
            deletions = allFiles.sumOf { it.deletions },
            files = kept,
            filesOmitted = (allFiles.size - kept.size).coerceAtLeast(0),
            areas = deriveAreas(allFiles),
            commitMessages = commitMessages,
            diffsIncluded = gitHubProperties.includeDiffs,
        )
    }

    private fun boundPatch(file: GitHubFileChange): GitHubFileChange =
        if (file.patch == null || file.patch.length <= contextProperties.maxPatchCharsPerFile) {
            file
        } else {
            file.copy(patch = file.patch.take(contextProperties.maxPatchCharsPerFile) + "\n… truncated")
        }

    /**
     * Groups changed paths into recognisable areas of the codebase.
     *
     * The directory immediately containing a file is what names the work: `config`, `reports`,
     * `migrations`. Leading path segments are the opposite — build layout (`src/main/kotlin`) and
     * reverse-domain package prefixes (`com/acme`) are identical across every file in the
     * organisation, so reading from the front yields the company name rather than the subject.
     */
    private fun deriveAreas(files: List<GitHubFileChange>): List<String> =
        files.mapNotNull { file ->
            file.path.split('/')
                .filter { it.isNotBlank() }
                .dropLast(1)
                .lastOrNull { it !in UNINFORMATIVE_SEGMENTS }
        }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(8)
            .map { it.key }

    private fun reviewState(matches: List<ActivityMatch>): ReviewState {
        val reviews = matches.flatMap { it.reviews }
        val open = matches.filter { it.pullRequest.state == PullRequestState.OPEN }

        return ReviewState(
            openPullRequests = open.size,
            mergedPullRequests = matches.count { it.pullRequest.state == PullRequestState.MERGED },
            draftPullRequests = matches.count { it.pullRequest.draft },
            approvals = reviews.count { it.state == "APPROVED" },
            changesRequested = reviews.count { it.state == "CHANGES_REQUESTED" },
            // Only open, non-draft work can be waiting on anyone.
            awaitingReview = open.any { !it.pullRequest.draft && it.reviews.isEmpty() },
            reviewers = reviews.mapNotNull { it.reviewer }.distinct(),
        )
    }

    private fun gaps(
        issue: JiraIssue,
        activity: TicketActivity,
        confirmed: List<ActivityMatch>,
    ): List<ContextGap> = buildList {
        activity.unavailableReason?.let {
            add(ContextGap(GapKind.GITHUB_UNAVAILABLE, "GitHub could not be reached: $it"))
        }

        if (activity.unavailableReason == null && activity.matches.isEmpty()) {
            add(
                ContextGap(
                    GapKind.NO_GITHUB_ACTIVITY,
                    "No pull request references ${activity.searchedKeys.joinToString(" or ")}. " +
                        "Code may exist without a ticket reference, or work may not have started.",
                ),
            )
        }

        val unconfirmed = activity.matches.filter { it.confidence == MatchConfidence.POSSIBLE_MATCH }
        if (unconfirmed.isNotEmpty()) {
            add(
                ContextGap(
                    GapKind.UNCERTAIN_CODE_LINK,
                    "${unconfirmed.size} pull request(s) may relate to this ticket but could not be " +
                        "confirmed: ${unconfirmed.joinToString(", ") { "${it.pullRequest.repository}#${it.pullRequest.number}" }}",
                ),
            )
        }

        if (issue.acceptanceCriteria == null) {
            add(
                ContextGap(
                    GapKind.NO_ACCEPTANCE_CRITERIA,
                    "This ticket states no acceptance criteria, so completion cannot be checked " +
                        "against a definition of done.",
                ),
            )
        }

        if (issue.description.isNullOrBlank()) {
            add(ContextGap(GapKind.NO_DESCRIPTION, "This ticket has no description."))
        }

        if (confirmed.isNotEmpty() && !gitHubProperties.includeDiffs) {
            add(
                ContextGap(
                    GapKind.DIFFS_EXCLUDED,
                    "File names and change counts are available; diff content is not.",
                ),
            )
        }

        val omitted = confirmed.flatMap { it.changedFiles }.size - contextProperties.maxFilesPerTicket
        if (omitted > 0) {
            add(ContextGap(GapKind.FILES_TRUNCATED, "$omitted further changed file(s) not listed."))
        }
    }

    private companion object {
        /** Segments that appear in every path and so distinguish nothing. */
        val UNINFORMATIVE_SEGMENTS = setOf(
            "src", "main", "test", "tests", "java", "kotlin", "com", "org", "net",
            "resources", "app", "lib", "source",
        )
    }
}
