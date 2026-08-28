package com.example.engineeringagent.service

import com.example.engineeringagent.agent.context.EngineeringContextAssembler
import com.example.engineeringagent.domain.ContextGap
import com.example.engineeringagent.domain.DailyWorkContext
import com.example.engineeringagent.domain.EngineeringContext
import com.example.engineeringagent.domain.GapKind
import com.example.engineeringagent.domain.JiraIssue
import com.example.engineeringagent.domain.MatchConfidence
import com.example.engineeringagent.domain.TicketActivity
import com.example.engineeringagent.exception.EngineeringAgentException
import com.example.engineeringagent.integration.github.GitHubActivityMatcher
import com.example.engineeringagent.integration.jira.JiraClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

@Service
class ContextService(
    private val jiraClient: JiraClient,
    private val matcher: GitHubActivityMatcher,
    private val activityService: ActivityService,
    private val assembler: EngineeringContextAssembler,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun getContext(ticketKey: String): EngineeringContext {
        val issue = withComments(ticketKey)
        val activity = activityService.getActivity(ticketKey, withDetail = true)
        return assembler.assemble(issue, activity)
    }

    /**
     * Context for everything currently active.
     *
     * Jira is the one hard dependency — without tickets there is nothing to report. Everything
     * downstream degrades per ticket, so one unreachable repository or a rate limit cannot take
     * the whole day's report with it.
     */
    fun getDailyContext(): DailyWorkContext {
        val issues = jiraClient.getInProgressIssues()
        val user = runCatching { jiraClient.getCurrentUser().displayName }.getOrNull()
        val gaps = mutableListOf<ContextGap>()

        val contexts = issues.mapNotNull { issue ->
            try {
                val withComments = runCatching { withComments(issue.key) }.getOrDefault(issue)
                assembler.assemble(withComments, activityFor(withComments))
            } catch (e: EngineeringAgentException) {
                log.warn("Could not build context for {}: {}", issue.key, e.message)
                gaps += ContextGap(
                    GapKind.GITHUB_UNAVAILABLE,
                    "${issue.key} could not be analysed: ${e.message}",
                )
                null
            }
        }

        if (issues.isEmpty()) {
            gaps += ContextGap(GapKind.NO_GITHUB_ACTIVITY, "No tickets are currently active.")
        }

        return DailyWorkContext(
            date = LocalDate.now(clock),
            user = user,
            contexts = contexts,
            gaps = gaps,
        )
    }

    /**
     * GitHub activity for one ticket, or an empty result carrying the reason it is missing.
     *
     * A GitHub outage, a rate limit or no token at all must not remove the ticket from the day's
     * report: the Jira side is still worth saying out loud. The assembler turns [unavailableReason]
     * into a gap, so the summary says "GitHub could not be reached" rather than "no code activity" --
     * the difference between unknown and none.
     */
    private fun activityFor(issue: JiraIssue): TicketActivity =
        try {
            activityService.detail(matcher.findActivity(issue))
        } catch (e: EngineeringAgentException) {
            log.warn("No GitHub activity for {}: {}", issue.key, e.message)
            TicketActivity(
                ticketKey = issue.key,
                confidence = MatchConfidence.NO_MATCH,
                matches = emptyList(),
                searchedKeys = emptyList(),
                unavailableReason = e.message,
            )
        }

    /** Comments are a separate Jira call, and often hold the pull request link matching relies on. */
    private fun withComments(ticketKey: String) =
        jiraClient.getIssue(ticketKey).let { issue ->
            issue.copy(
                comments = runCatching { jiraClient.getIssueComments(ticketKey) }
                    .getOrDefault(emptyList()),
            )
        }
}
