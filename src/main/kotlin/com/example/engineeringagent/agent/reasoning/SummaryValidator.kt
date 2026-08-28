package com.example.engineeringagent.agent.reasoning

import com.example.engineeringagent.domain.DailyWorkSummary
import com.example.engineeringagent.domain.EngineeringContext
import com.example.engineeringagent.domain.GapKind
import com.example.engineeringagent.domain.MatchConfidence
import com.example.engineeringagent.domain.StatusConsistency
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Checks a model's output against the evidence that produced it.
 *
 * Schema conformance proves the shape is right, not that the content is true. These checks catch
 * the failures that actually occur: claiming completed work for a ticket with no code, echoing the
 * wrong ticket key, and returning confidence uncorrelated with the evidence.
 *
 * The validator corrects what it safely can and records every correction in `notes`, so a reader
 * can see where the model was overruled. It rejects outright only when the output is unusable.
 */
@Component
class SummaryValidator {

    private val log = LoggerFactory.getLogger(javaClass)

    fun validate(raw: JsonNode, context: EngineeringContext): DailyWorkSummary {
        val summaryText = raw.path("summary").asText("").trim()
        if (summaryText.isBlank()) {
            throw InvalidSummaryException("model returned an empty summary")
        }

        val corrections = mutableListOf<String>()

        // The model occasionally echoes a key it saw in the text rather than the ticket it was
        // asked about. The caller knows which ticket this is; the model's opinion does not count.
        val ticketKey = raw.path("ticketKey").asText("").trim()
        if (ticketKey != context.ticketKey) {
            log.warn("Model reported ticket '{}' for context '{}'", ticketKey, context.ticketKey)
            corrections += "Model reported the wrong ticket key; corrected to ${context.ticketKey}."
        }

        var completed = strings(raw, "completed")
        var confidence = raw.path("confidence").asDouble(0.0).coerceIn(0.0, 1.0)

        val hasConfirmedCode = context.activity.matches.any { it.confidence == MatchConfidence.MATCHED }

        // Claiming finished work for a ticket with no code evidence is the failure this whole
        // system exists to avoid, so it is dropped rather than merely flagged.
        //
        // The test is the absence of confirmed code, not the presence of a "no activity" gap.
        // Those differ whenever GitHub could not be reached: there is no such gap then, and
        // keying off one let an unevidenced claim through in exactly the case where the evidence
        // is weakest.
        if (completed.isNotEmpty() && !hasConfirmedCode) {
            log.warn(
                "Dropping {} completed claim(s) for {}: no code evidence supports them",
                completed.size, context.ticketKey,
            )
            corrections += "Removed ${completed.size} claim(s) of completed work: no confirmed " +
                "code evidence supports them."
            completed = emptyList()
        }

        if (!hasConfirmedCode && confidence > NO_EVIDENCE_CEILING) {
            corrections += "Lowered confidence: no pull request is confirmed for this ticket."
            confidence = NO_EVIDENCE_CEILING
        }

        val inProgress = strings(raw, "inProgress")

        // Small models describe merged work as still open, contradicting evidence the prompt states
        // outright. The claim is not deleted — work can be in progress without an open pull request
        // — but a reader must not take it as code-backed when the code says otherwise.
        if (inProgress.isNotEmpty() && hasConfirmedCode &&
            context.reviewState.openPullRequests == 0 && context.reviewState.draftPullRequests == 0
        ) {
            corrections += "No pull request is currently open for this ticket; any work described " +
                "as in progress is not evidenced by the code."
        }

        // Last line of defence for the prompt rule above: a model that reports the agent's own
        // setup as engineering work sends a reader chasing the wrong problem, and a stand-up line
        // saying "blocked on GitHub configuration" is worse than one that says nothing.
        val stated = listOf("blockers", "nextSteps", "remaining").associateWith { strings(raw, it) }
        val kept = stated.mapValues { (_, items) -> items.withoutOperationalNoise() }
        val trimmed = stated.keys.filter { kept.getValue(it).size < stated.getValue(it).size }
        if (trimmed.isNotEmpty()) {
            log.warn("Dropped setup-related item(s) from {} for {}", trimmed, context.ticketKey)
            corrections += "Removed item(s) describing this agent's own configuration rather than the work."
        }

        // Resolved before `notes` is assembled: it can add a correction, and notes is a snapshot.
        val consistency = statusConsistency(raw, context, corrections)

        val notes = strings(raw, "notes") + corrections + carriedGaps(context)

        return DailyWorkSummary(
            ticketKey = context.ticketKey,
            summary = summaryText,
            completed = completed,
            inProgress = inProgress,
            remaining = kept.getValue("remaining"),
            blockers = kept.getValue("blockers"),
            nextSteps = kept.getValue("nextSteps"),
            statusConsistency = consistency,
            confidence = confidence,
            notes = notes.distinct(),
        )
    }

    /**
     * Gaps the reader needs regardless of whether the model mentioned them. These are facts about
     * the evidence, so they are carried through rather than left to the model's discretion.
     */
    private fun carriedGaps(context: EngineeringContext): List<String> =
        context.gaps
            .filter { it.kind in CARRIED_GAPS }
            .map { it.detail }

    /**
     * Extracts a list field, discarding entries that are not actual statements.
     *
     * Small models tend to echo the vocabulary of the evidence rather than describe it, answering
     * "what was completed?" with "merged". A bare state word is not a claim anyone can repeat in a
     * stand-up, and dropping it is better than reporting it.
     */
    private fun strings(raw: JsonNode, field: String): List<String> =
        raw.path(field)
            .mapNotNull { it.textValue()?.trim() }
            .filter { it.isNotBlank() && !it.equals(DailyWorkSummary.UNKNOWN, ignoreCase = true) }
            .filter { it.contains(' ') && it.length >= MIN_STATEMENT_LENGTH }
            .distinct()

    /**
     * Whether the Jira status matches the work.
     *
     * Judging this needs to know what the work was. When GitHub could not be reached that is
     * exactly what is missing, so any verdict is guesswork however confidently stated — and unlike
     * a missing pull request, a wrong consistency claim reads as a judgement about the developer.
     *
     * GitHub being checked and finding nothing is a different case, and is left alone: a ticket
     * sitting In Progress with no code may genuinely be inconsistent, and saying so is useful.
     */
    private fun statusConsistency(
        raw: JsonNode,
        context: EngineeringContext,
        corrections: MutableList<String>,
    ): StatusConsistency {
        val stated = runCatching { StatusConsistency.valueOf(raw.path("statusConsistency").asText("UNKNOWN")) }
            .getOrDefault(StatusConsistency.UNKNOWN)

        if (context.activity.unavailableReason != null && stated != StatusConsistency.UNKNOWN) {
            log.warn(
                "Model judged status {} for {} without code evidence; recording UNKNOWN",
                stated, context.ticketKey,
            )
            corrections += "Could not judge whether the Jira status matches the work: the code was never checked."
            return StatusConsistency.UNKNOWN
        }
        return stated
    }

    /**
     * Drops items that describe the agent's own configuration rather than the engineering work.
     *
     * Matching on the names of this system's own settings is narrow on purpose. A broader rule
     * ("mentions GitHub") would delete legitimate statements like "waiting on a GitHub review".
     */
    private fun List<String>.withoutOperationalNoise(): List<String> =
        filterNot { item -> OPERATIONAL_TERMS.any { item.contains(it, ignoreCase = true) } }

    private companion object {
        const val NO_EVIDENCE_CEILING = 0.3

        /** Settings of this agent. None of them is ever a developer's blocker. */
        val OPERATIONAL_TERMS = listOf(
            "GITHUB_TOKEN", "GITHUB_ORG", "JIRA_API_TOKEN", "SLACK_BOT_TOKEN", "AI_API_KEY",
            "is not configured", "not configured:", "configure github", "configure jira",
        )

        /** Below this, an entry is a label rather than a statement. */
        const val MIN_STATEMENT_LENGTH = 12
        val CARRIED_GAPS = setOf(
            GapKind.GITHUB_UNAVAILABLE,
            GapKind.UNCERTAIN_CODE_LINK,
            GapKind.NO_GITHUB_ACTIVITY,
        )
    }
}
