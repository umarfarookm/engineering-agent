package com.example.engineeringagent.agent.reasoning

import com.example.engineeringagent.domain.CodeChangeSummary
import com.example.engineeringagent.domain.ContextGap
import com.example.engineeringagent.domain.EngineeringContext
import com.example.engineeringagent.domain.GapKind
import com.example.engineeringagent.domain.JiraIssue
import com.example.engineeringagent.domain.MatchConfidence
import com.example.engineeringagent.domain.ReviewState
import com.example.engineeringagent.domain.TicketActivity
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * This class decides what the model is allowed to see, so its output is a security and correctness
 * surface, not a formatting detail.
 */
class ContextPromptRendererTest {

    private val renderer = ContextPromptRenderer()

    private fun context(gaps: List<ContextGap> = emptyList()) = EngineeringContext(
        ticketKey = "ENG-185",
        issue = JiraIssue(
            id = "1", key = "ENG-185", summary = "Upgrade to Spring Boot 4.1",
            description = "Bump the framework.", status = "In Progress", statusCategory = null,
            assignee = null, priority = "Medium", issueType = "Task", acceptanceCriteria = null,
            created = null, updated = null, url = null,
        ),
        activity = TicketActivity("ENG-185", MatchConfidence.NO_MATCH, emptyList(), listOf("ENG-185")),
        codeChanges = CodeChangeSummary(0, 0, 0, 0, 0, emptyList(), 0, emptyList(), emptyList(), false),
        reviewState = ReviewState(0, 0, 0, 0, 0, false, emptyList()),
        gaps = gaps,
        assembledAt = Instant.parse("2026-08-28T09:00:00Z"),
    )

    /**
     * The stored detail names environment variables so whoever runs the agent can fix it. Shown
     * that text, a model reports "set GITHUB_TOKEN" as the developer's next step — observed from
     * qwen2.5:7b on a real ticket.
     */
    @Test
    fun `never shows the model how to fix the agent's own configuration`() {
        val prompt = renderer.render(
            context(
                listOf(
                    ContextGap(
                        GapKind.GITHUB_UNAVAILABLE,
                        "GitHub could not be reached: GitHub is not configured. Set GITHUB_TOKEN and GITHUB_ORG.",
                    ),
                ),
            ),
        )

        assertFalse(prompt.contains("GITHUB_TOKEN"), prompt)
        assertFalse(prompt.contains("GITHUB_ORG"), prompt)
    }

    /** Suppressing the remediation must not suppress the limit itself, or absence reads as proof. */
    @Test
    fun `still tells the model that code activity was not checked`() {
        val prompt = renderer.render(
            context(listOf(ContextGap(GapKind.GITHUB_UNAVAILABLE, "GitHub is not configured."))),
        )

        assertTrue(prompt.contains("LIMITS OF THIS EVIDENCE"), prompt)
        assertTrue(prompt.contains("could not be checked"), prompt)
        assertTrue(prompt.contains("unknown, not confirmed"), prompt)
        assertTrue(prompt.contains("never be reported as blockers"), prompt)
    }

    /** A gap in the work is genuinely about the ticket, and belongs where the model can use it. */
    @Test
    fun `keeps gaps in the work in the context gaps section`() {
        val prompt = renderer.render(
            context(listOf(ContextGap(GapKind.NO_ACCEPTANCE_CRITERIA, "This ticket states no acceptance criteria."))),
        )

        val gapsSection = prompt.substringAfter("# CONTEXT GAPS").substringBefore("# LIMITS")
        assertTrue(gapsSection.contains("no acceptance criteria"), prompt)
    }

    @Test
    fun `omits the limits section entirely when everything was checked`() {
        val prompt = renderer.render(context())

        assertFalse(prompt.contains("LIMITS OF THIS EVIDENCE"), prompt)
        assertTrue(prompt.contains("as complete as the sources allow"), prompt)
    }
}
