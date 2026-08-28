package com.example.engineeringagent.service

import com.example.engineeringagent.domain.MatchConfidence
import com.example.engineeringagent.domain.TicketActivity
import com.example.engineeringagent.integration.github.GitHubActivityMatcher
import com.example.engineeringagent.integration.github.GitHubClient
import com.example.engineeringagent.integration.jira.JiraClient
import org.springframework.stereotype.Service

@Service
class ActivityService(
    private val jiraClient: JiraClient,
    private val matcher: GitHubActivityMatcher,
    private val gitHubClient: GitHubClient,
) {

    /**
     * GitHub activity for one ticket. [withDetail] additionally loads commits, changed files and
     * reviews for confirmed matches — extra API calls that only pay off once a link is trusted.
     */
    fun getActivity(ticketKey: String, withDetail: Boolean = false): TicketActivity {
        val issue = jiraClient.getIssue(ticketKey).let { base ->
            // Comments are a separate call but often hold the PR link, so strategy 0 needs them.
            base.copy(comments = runCatching { jiraClient.getIssueComments(ticketKey) }.getOrDefault(emptyList()))
        }

        val activity = matcher.findActivity(issue)
        return if (withDetail) detail(activity) else activity
    }

    /** Loads commits, changed files and reviews for the confirmed matches of an activity. */
    fun detail(activity: TicketActivity): TicketActivity =
        activity.copy(
            matches = activity.matches.map { match ->
                if (match.confidence != MatchConfidence.MATCHED) return@map match
                val (owner, repo) = match.pullRequest.repository.split("/", limit = 2)
                    .takeIf { it.size == 2 } ?: return@map match
                val number = match.pullRequest.number
                match.copy(
                    commits = runCatching { gitHubClient.getPullRequestCommits(owner, repo, number) }
                        .getOrDefault(emptyList()),
                    changedFiles = runCatching { gitHubClient.getChangedFiles(owner, repo, number) }
                        .getOrDefault(emptyList()),
                    reviews = runCatching { gitHubClient.getReviews(owner, repo, number) }
                        .getOrDefault(emptyList()),
                )
            },
        )
}
