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
 * Talks to the Anthropic Messages API.
 *
 * Unlike Ollama this is a hosted provider: the prompt — ticket titles, descriptions, branch names,
 * file paths — leaves the machine. That is a policy decision, not a technical one, which is why
 * [isLocal] is false and the startup log says so plainly. See docs/SECURITY.md.
 *
 * `output_config.format` constrains generation to the schema, so the response text is valid JSON
 * without prompting for it.
 */
class AnthropicLlmClient(
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
            put("max_tokens", properties.maxTokens)
            put("temperature", properties.temperature)
            put("system", systemPrompt)
            set<JsonNode>(
                "messages",
                objectMapper.createArrayNode().add(
                    objectMapper.createObjectNode().put("role", "user").put("content", userPrompt),
                ),
            )
            set<JsonNode>(
                "output_config",
                objectMapper.createObjectNode().set(
                    "format",
                    objectMapper.createObjectNode()
                        .put("type", "json_schema")
                        .set<JsonNode>("schema", schema),
                ),
            )
        }

        val response = try {
            restClient.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                // Bodies can echo the prompt, which is the ticket text; only the status is surfaced.
                .onStatus(HttpStatusCode::isError) { _, r ->
                    throw AiUnavailableException("Anthropic returned ${r.statusCode}")
                }
                .body(JsonNode::class.java)
                ?: throw AiUnavailableException("Anthropic returned an empty body")
        } catch (e: RestClientException) {
            throw AiUnavailableException("Could not reach Anthropic at ${properties.resolvedBaseUrl()}", e)
        }

        log.info(
            "Anthropic {} used {} input / {} output token(s)",
            properties.model,
            response.path("usage").path("input_tokens").asInt(0),
            response.path("usage").path("output_tokens").asInt(0),
        )

        val text = response.path("content")
            .firstOrNull { it.path("type").asText() == "text" }
            ?.path("text")?.asText().orEmpty()
        if (text.isBlank()) throw AiUnavailableException("Anthropic returned no text content")

        return runCatching { objectMapper.readTree(text) }
            .getOrElse { throw AiUnavailableException("Anthropic returned content that is not valid JSON") }
    }
}
