package com.example.engineeringagent.agent.summary

import com.example.engineeringagent.domain.DailyReport
import com.example.engineeringagent.domain.DailyWorkSummary
import com.example.engineeringagent.domain.SummarySource
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DailyStatusFormatterTest {

    private val formatter = DailyStatusFormatter()

    private fun summary(
        key: String = "ENG-185",
        text: String = "The Spring Boot upgrade is merged.",
        completed: List<String> = emptyList(),
        inProgress: List<String> = emptyList(),
        nextSteps: List<String> = emptyList(),
        remaining: List<String> = emptyList(),
        blockers: List<String> = emptyList(),
        confidence: Double = 0.8,
        source: SummarySource = SummarySource.LLM,
        notes: List<String> = emptyList(),
    ) = DailyWorkSummary(
        ticketKey = key, summary = text, completed = completed, inProgress = inProgress,
        remaining = remaining, blockers = blockers, nextSteps = nextSteps,
        confidence = confidence, generatedBy = source, notes = notes,
    )

    private fun report(vararg summaries: DailyWorkSummary, notes: List<String> = emptyList()) =
        DailyReport(LocalDate.of(2026, 8, 25), "Alex Rivera", summaries.toList(), notes)

    @Test
    fun `renders a ticket as a skimmable block`() {
        val output = formatter.format(
            report(
                summary(
                    completed = listOf("Merged the Spring Boot 4.1 upgrade"),
                    nextSteps = listOf("Verify the deployment"),
                ),
            ),
        )

        assertTrue(output.contains("*Daily status — Tue 25 Aug*"), output)
        assertTrue(output.contains("*ENG-185* — The Spring Boot upgrade is merged."), output)
        assertTrue(output.contains("Completed:"), output)
        assertTrue(output.contains("• Merged the Spring Boot 4.1 upgrade"), output)
        assertTrue(output.contains("• Verify the deployment"), output)
    }

    @Test
    fun `omits empty sections rather than printing None`() {
        val output = formatter.formatTicket(summary(completed = listOf("Merged the upgrade")))

        assertFalse(output.contains("In progress"), output)
        assertFalse(output.contains("Remaining"), output)
    }

    @Test
    fun `states plainly when there are no blockers`() {
        val output = formatter.format(report(summary(completed = listOf("Merged the upgrade"))))
        assertTrue(output.contains("No blockers."), output)
    }

    @Test
    fun `collects blockers across tickets into one section`() {
        val output = formatter.format(
            report(
                summary(key = "ENG-185", blockers = listOf("Waiting on the platform team")),
                summary(key = "ENG-80", blockers = listOf("Blocked on API key rotation")),
            ),
        )

        assertTrue(output.contains("*Blockers*"), output)
        assertTrue(output.contains("• Waiting on the platform team"), output)
        assertTrue(output.contains("• Blocked on API key rotation"), output)
    }

    @Test
    fun `marks a low-confidence summary so it is not read as settled`() {
        val output = formatter.formatTicket(
            summary(confidence = 0.2, nextSteps = listOf("Confirm the root cause")),
        )
        assertTrue(output.contains("Limited evidence"), output)
    }

    @Test
    fun `marks a deterministic summary as unanalysed`() {
        val output = formatter.formatTicket(
            summary(source = SummarySource.DETERMINISTIC, completed = listOf("Merged the upgrade")),
        )
        assertTrue(output.contains("without analysis"), output)
    }

    @Test
    fun `says when a ticket has no code activity`() {
        val output = formatter.formatTicket(summary(key = "ENG-267", text = "Refund bug.", confidence = 0.8))
        assertTrue(output.contains("No code activity found"), output)
    }

    @Test
    fun `keeps developer-facing notes out of the message`() {
        // Caveats belong in the preview for the person approving, not in the channel.
        val output = formatter.formatTicket(
            summary(
                completed = listOf("Merged the upgrade"),
                notes = listOf("Model reported the wrong ticket key; corrected to ENG-185."),
            ),
        )

        assertFalse(output.contains("wrong ticket key"), output)
    }

    @Test
    fun `handles a day with no active tickets`() {
        val output = formatter.format(report(notes = listOf("No tickets are currently active.")))

        assertTrue(output.contains("No active tickets today."), output)
        assertFalse(output.contains("No blockers."), "an empty day needs no blocker section")
    }

    @Test
    fun `does not double punctuate list items`() {
        val output = formatter.formatTicket(summary(completed = listOf("Merged the upgrade.")))
        assertTrue(output.contains("• Merged the upgrade\n"), output)
    }

    @Test
    fun `produces one line per ticket heading for a multi-ticket day`() {
        val output = formatter.format(
            report(
                summary(key = "ENG-185", completed = listOf("Merged the upgrade")),
                summary(key = "ENG-80", completed = listOf("Merged the retry work")),
                summary(key = "ENG-267", text = "Refund bug."),
            ),
        )

        assertEquals(3, output.lines().count { it.startsWith("*ENG-") })
    }
}
