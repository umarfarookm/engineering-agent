package com.example.engineeringagent.agent.summary

import com.example.engineeringagent.domain.DailyReport
import com.example.engineeringagent.domain.DailyWorkSummary
import com.example.engineeringagent.domain.SummarySource
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders summaries as a Slack message.
 *
 * This is the only part of the system other people read, so it is written to be skimmed in a
 * stand-up: ticket, state, what happened, what is next. Caveats appear only when they would change
 * how someone reads the message — a note on every ticket trains readers to ignore all of them.
 *
 * Formatting is deliberately deterministic rather than model-generated. The model decides what is
 * true; how it reads is a decision that should not vary run to run.
 */
@Component
class DailyStatusFormatter {

    fun format(report: DailyReport): String = buildString {
        appendLine("*Daily status — ${report.date.format(DATE)}*")

        if (report.summaries.isEmpty()) {
            appendLine()
            appendLine("No active tickets today.")
            report.notes.forEach { appendLine("_${it}_") }
            return@buildString
        }

        report.summaries.forEach { summary ->
            appendLine()
            append(formatTicket(summary))
        }

        val blocked = report.summaries.flatMap { it.blockers }
        appendLine()
        appendLine(if (blocked.isEmpty()) "No blockers." else "*Blockers*")
        blocked.forEach { appendLine("• $it") }
    }

    fun formatTicket(summary: DailyWorkSummary): String = buildString {
        appendLine("*${summary.ticketKey}* — ${summary.summary.trim()}")

        section("Completed", summary.completed)
        section("In progress", summary.inProgress)
        section("Next", summary.nextSteps)
        section("Remaining", summary.remaining)

        caveat(summary)?.let { appendLine("_${it}_") }
    }

    private fun StringBuilder.section(label: String, items: List<String>) {
        if (items.isEmpty()) return
        appendLine("$label:")
        items.forEach { appendLine("• ${it.trimEnd('.')}") }
    }

    /**
     * A single line covering only what would change the reader's trust in the message.
     *
     * The full `notes` list is for the developer reviewing a summary before sending, not for the
     * channel — publishing every caveat makes the message unreadable and, worse, unread.
     */
    private fun caveat(summary: DailyWorkSummary): String? = when {
        summary.generatedBy == SummarySource.DETERMINISTIC ->
            "Generated from Jira and GitHub directly, without analysis."
        summary.confidence < LOW_CONFIDENCE ->
            "Limited evidence for this ticket — worth checking before relying on it."
        summary.completed.isEmpty() && summary.inProgress.isEmpty() && summary.nextSteps.isEmpty() ->
            "No code activity found for this ticket."
        else -> null
    }

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
        const val LOW_CONFIDENCE = 0.4
    }
}
