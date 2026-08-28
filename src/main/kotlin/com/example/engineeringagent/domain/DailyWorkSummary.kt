package com.example.engineeringagent.domain

import java.time.Instant

/**
 * The reasoned account of one ticket's state.
 *
 * Every list is a claim about reality that someone will repeat in a stand-up, so the model is held
 * to the evidence: it may only state what the [EngineeringContext] supports, and must use
 * [UNKNOWN] rather than guess. [notes] carries the caveats — what could not be determined, and why.
 */
data class DailyWorkSummary(
    val ticketKey: String,
    val summary: String,
    val completed: List<String> = emptyList(),
    val inProgress: List<String> = emptyList(),
    val remaining: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
    val nextSteps: List<String> = emptyList(),
    /** Whether the Jira status matches what the code evidence actually shows. */
    val statusConsistency: StatusConsistency = StatusConsistency.UNKNOWN,
    val confidence: Double = 0.0,
    /** Caveats and named absences, carried through from the context's gaps. */
    val notes: List<String> = emptyList(),
    val generatedBy: SummarySource = SummarySource.LLM,
    val generatedAt: Instant? = null,
) {
    companion object {
        const val UNKNOWN = "Unknown"
    }
}

enum class StatusConsistency { CONSISTENT, INCONSISTENT, UNKNOWN }

/** How a summary was produced — a deterministic fallback must never be mistaken for reasoning. */
enum class SummarySource {
    LLM,
    /** Built from the evidence alone, because AI was disabled or unavailable. */
    DETERMINISTIC,
}

data class DailyReport(
    val date: java.time.LocalDate,
    val user: String?,
    val summaries: List<DailyWorkSummary>,
    val notes: List<String> = emptyList(),
)
