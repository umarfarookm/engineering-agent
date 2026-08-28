package com.example.engineeringagent.integration.ai

import com.example.engineeringagent.config.AiProperties
import com.example.engineeringagent.exception.AiUnavailableException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * Talks to the OpenAI Chat Completions API, and to anything that speaks it.
 *
 * The endpoint is the de-facto standard, so pointing `ai.base-url` at a compatible gateway — vLLM,
 * LM Studio, an internal proxy — works without another client. Whether that destination is on this
 * machine is a question only the operator can answer, so [isLocal] stays false: the safe assumption
 * for a configurable endpoint is that data leaves.
 */
class OpenAiLlmClient(
    private val restClient: RestClient,
    private val properties: AiProperties,
    private val objectMapper: ObjectMapper,
) : LlmClient {

    private val log = LoggerFactory.getLogger(javaClass)

    override val model: String get() = properties.model
    override val isLocal: Boolean get() = false

    override fun completeJson(systemPrompt: String, userPrompt: String, schema: JsonNode): JsonNode {
        val request = objectMapper.createObjectNode().apply {
            put("model", properties.model)
            put("temperature", properties.temperature)
            put("max_completion_tokens", properties.maxTokens)
            set<JsonNode>(
                "messages",
                objectMapper.createArrayNode().apply {
                    add(objectMapper.createObjectNode().put("role", "system").put("content", systemPrompt))
                    add(objectMapper.createObjectNode().put("role", "user").put("content", userPrompt))
                },
            )
            set<JsonNode>(
                "response_format",
                objectMapper.createObjectNode()
                    .put("type", "json_schema")
                    .set<JsonNode>(
                        "json_schema",
                        objectMapper.createObjectNode()
                            .put("name", "daily_work_summary")
                            .put("strict", true)
                            .set<JsonNode>("schema", schema),
                    ),
            )
        }

        val response = try {
            restClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                // Bodies can echo the prompt, which is the ticket text; only the status is surfaced.
                .onStatus(HttpStatusCode::isError) { _, r ->
                    throw AiUnavailableException("OpenAI returned ${r.statusCode}")
                }
                .body(JsonNode::class.java)
                ?: throw AiUnavailableException("OpenAI returned an empty body")
        } catch (e: RestClientException) {
            throw AiUnavailableException("Could not reach OpenAI at ${properties.resolvedBaseUrl()}", e)
        }

        log.info(
            "OpenAI {} used {} prompt / {} completion token(s)",
            properties.model,
            response.path("usage").path("prompt_tokens").asInt(0),
            response.path("usage").path("completion_tokens").asInt(0),
        )

        val message = response.path("choices").firstOrNull()?.path("message")
        // A refusal is a deliberate answer, not a transport failure, but it carries no summary.
        message?.path("refusal")?.takeIf { it.isTextual }?.let {
            throw AiUnavailableException("OpenAI refused the request")
        }

        val content = message?.path("content")?.asText().orEmpty()
        if (content.isBlank()) throw AiUnavailableException("OpenAI returned no content")

        return runCatching { objectMapper.readTree(content) }
            .getOrElse { throw AiUnavailableException("OpenAI returned content that is not valid JSON") }
    }
}
