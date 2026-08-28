package com.example.engineeringagent.service

import com.example.engineeringagent.agent.context.EngineeringContextAssembler
import com.example.engineeringagent.config.ContextProperties
import com.example.engineeringagent.config.GitHubProperties
import com.example.engineeringagent.domain.GapKind
import com.example.engineeringagent.domain.JiraIssue
import com.example.engineeringagent.exception.GitHubNotConfiguredException
import com.example.engineeringagent.integration.github.GitHubActivityMatcher
import com.example.engineeringagent.integration.jira.JiraClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ContextServiceTest {

    private val jiraClient = mock(JiraClient::class.java)
    private val matcher = mock(GitHubActivityMatcher::class.java)
    private val activityService = mock(ActivityService::class.java)
    private val clock = Clock.fixed(Instant.parse("2026-08-28T09:00:00Z"), ZoneOffset.UTC)

    private val service = ContextService(
        jiraClient = jiraClient,
        matcher = matcher,
        activityService = activityService,
        assembler = EngineeringContextAssembler(GitHubProperties(), ContextProperties(), clock),
        clock = clock,
    )

    private val issue = JiraIssue(
        id = "1",
        key = "ENG-267",
        summary = "Monthly email reports",
        description = "Send a monthly summary.",
        status = "In Progress",
        statusCategory = "indeterminate",
        assignee = null,
        priority = "Medium",
        issueType = "Story",
        acceptanceCriteria = null,
        created = null,
        updated = null,
        url = null,
    )

    /**
     * Losing GitHub must not lose the ticket. Dropping it would empty the whole report, and the
     * scheduled workflow reads an empty report as "nothing to say today" and stays silent.
     */
    @Test
    fun `keeps the ticket when GitHub is not configured`() {
        `when`(jiraClient.getInProgressIssues()).thenReturn(listOf(issue))
        `when`(jiraClient.getIssue("ENG-267")).thenReturn(issue)
        `when`(jiraClient.getIssueComments("ENG-267")).thenReturn(emptyList())
        `when`(matcher.findActivity(issue))
            .thenThrow(GitHubNotConfiguredException("GitHub is not configured."))

        val daily = service.getDailyContext()

        assertThat(daily.contexts).hasSize(1)
        assertThat(daily.contexts.first().ticketKey).isEqualTo("ENG-267")
    }

    /** "Unknown" and "none" are different claims; the summary must not confuse them. */
    @Test
    fun `records GitHub being unreachable as a gap rather than absent activity`() {
        `when`(jiraClient.getInProgressIssues()).thenReturn(listOf(issue))
        `when`(jiraClient.getIssue("ENG-267")).thenReturn(issue)
        `when`(jiraClient.getIssueComments("ENG-267")).thenReturn(emptyList())
        `when`(matcher.findActivity(issue))
            .thenThrow(GitHubNotConfiguredException("GitHub is not configured."))

        val gaps = service.getDailyContext().contexts.first().gaps

        assertThat(gaps.map { it.kind }).contains(GapKind.GITHUB_UNAVAILABLE)
        assertThat(gaps.map { it.kind }).doesNotContain(GapKind.NO_GITHUB_ACTIVITY)
    }
}
