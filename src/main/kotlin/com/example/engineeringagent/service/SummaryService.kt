package com.example.engineeringagent.service

import com.example.engineeringagent.agent.reasoning.EngineeringReasoningService
import com.example.engineeringagent.domain.DailyReport
import com.example.engineeringagent.domain.DailyWorkSummary
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SummaryService(
    private val contextService: ContextService,
    private val reasoningService: EngineeringReasoningService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun summarize(ticketKey: String): DailyWorkSummary =
        reasoningService.summarize(contextService.getContext(ticketKey))

    /** One summary per active ticket. A failure on one ticket must not lose the others. */
    fun dailyReport(): DailyReport {
        val daily = contextService.getDailyContext()
        val notes = daily.gaps.map { it.detail }.toMutableList()

        val summaries = daily.contexts.mapNotNull { context ->
            runCatching { reasoningService.summarize(context) }
                .onFailure {
                    log.warn("Could not summarize {}: {}", context.ticketKey, it.message)
                    notes += "${context.ticketKey} could not be summarized: ${it.message}"
                }
                .getOrNull()
        }

        return DailyReport(date = daily.date, user = daily.user, summaries = summaries, notes = notes)
    }
}
