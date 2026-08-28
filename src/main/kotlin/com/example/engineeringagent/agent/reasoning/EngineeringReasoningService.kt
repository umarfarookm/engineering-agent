package com.example.engineeringagent.agent.reasoning

import com.example.engineeringagent.config.AiProperties
import com.example.engineeringagent.domain.DailyWorkSummary
import com.example.engineeringagent.domain.EngineeringContext
import com.example.engineeringagent.domain.SummarySource
import com.example.engineeringagent.exception.AiUnavailableException
import com.example.engineeringagent.integration.ai.LlmClient
import com.example.engineeringagent.integration.ai.PromptLoader
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * Turns assembled evidence into a reported summary.
 *
 * The model is never the last word. Its output is validated against the evidence that produced it,
 * and if it cannot be trusted the service falls back to a deterministic summary rather than
 * returning something plausible and wrong.
 */
@Service
class EngineeringReasoningService(
    private val llmClient: LlmClient,
    private val promptLoader: PromptLoader,
    private val renderer: ContextPromptRenderer,
    private val validator: SummaryValidator,
    private val fallback: DeterministicSummarizer,
    private val properties: AiProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun summarize(context: EngineeringContext): DailyWorkSummary {
        if (!properties.enabled) {
            log.info("AI disabled; producing a deterministic summary for {}", context.ticketKey)
            return fallback.summarize(context)
        }

        val systemPrompt = promptLoader.load("daily-summary-system.md")
        val userPrompt = renderer.render(context)

        repeat(properties.maxAttempts) { attempt ->
            try {
                val raw = llmClient.completeJson(systemPrompt, userPrompt, schema())
                return validator.validate(raw, context).copy(
                    generatedBy = SummarySource.LLM,
                    generatedAt = Instant.now(clock),
                )
            } catch (e: AiUnavailableException) {
                log.warn("AI attempt {} failed for {}: {}", attempt + 1, context.ticketKey, e.message)
            } catch (e: InvalidSummaryException) {
                log.warn("AI attempt {} produced unusable output for {}: {}", attempt + 1, context.ticketKey, e.message)
            }
        }

        log.warn("Falling back to a deterministic summary for {}", context.ticketKey)
        return fallback.summarize(context).let {
            it.copy(notes = it.notes + "AI summary unavailable; this was generated from the evidence directly.")
        }
    }

    /**
     * The schema the provider constrains decoding to. Kept in code rather than a resource because
     * it must stay in step with [DailyWorkSummary] — a drift between them is a runtime failure.
     *
     * Every property is listed in `required` and `additionalProperties` is false because OpenAI's
     * strict mode rejects a schema that omits either. Ollama and Anthropic accept the same shape,
     * so one schema serves all three rather than one per provider.
     */
    private fun schema(): JsonNode = objectMapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "ticketKey":   { "type": "string" },
            "summary":     { "type": "string" },
            "completed":   { "type": "array", "items": { "type": "string" } },
            "inProgress":  { "type": "array", "items": { "type": "string" } },
            "remaining":   { "type": "array", "items": { "type": "string" } },
            "blockers":    { "type": "array", "items": { "type": "string" } },
            "nextSteps":   { "type": "array", "items": { "type": "string" } },
            "statusConsistency": { "type": "string", "enum": ["CONSISTENT", "INCONSISTENT", "UNKNOWN"] },
            "confidence":  { "type": "number" },
            "notes":       { "type": "array", "items": { "type": "string" } }
          },
          "required": ["ticketKey", "summary", "completed", "inProgress", "remaining",
                       "blockers", "nextSteps", "statusConsistency", "confidence", "notes"],
          "additionalProperties": false
        }
        """.trimIndent(),
    )
}

class InvalidSummaryException(message: String) : RuntimeException(message)
