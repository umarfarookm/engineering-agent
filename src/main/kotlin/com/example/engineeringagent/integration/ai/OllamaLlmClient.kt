package com.example.engineeringagent.integration.ai

import com.example.engineeringagent.config.AiProperties
import com.example.engineeringagent.exception.AiUnavailableException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * Talks to a local Ollama instance.
 *
 * Being local is the point: the ticket text, commit messages and file paths in a prompt never leave
 * the machine, which removes the external-processor problem entirely rather than mitigating it.
 *
 * Ollama accepts a JSON Schema in its `format` field and constrains decoding to match. That matters
 * far more for small models than large ones — a 3B model asked politely for JSON will produce prose
 * about half the time, while one decoding under a schema cannot.
 */
@Component
class OllamaLlmClient(
    private val ollamaRestClient: RestClient,
    private val properties: AiProperties,
    private val objectMapper: ObjectMapper,
) : LlmClient {

    private val log = LoggerFactory.getLogger(javaClass)

    override val model: String get() = properties.model
    override val isLocal: Boolean get() = true

    override fun completeJson(systemPrompt: String, userPrompt: String, schema: JsonNode): JsonNode {
        val request = objectMapper.createObjectNode().apply {
            put("model", properties.model)
            put("stream", false)
            set<JsonNode>("format", schema)
            set<JsonNode>(
                "options",
                objectMapper.createObjectNode().put("temperature", properties.temperature),
            )
            set<JsonNode>(
                "messages",
                objectMapper.createArrayNode().apply {
                    add(objectMapper.createObjectNode().put("role", "system").put("content", systemPrompt))
                    add(objectMapper.createObjectNode().put("role", "user").put("content", userPrompt))
                },
            )
        }

        val response = try {
            ollamaRestClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError) { _, r ->
                    throw AiUnavailableException("Ollama returned ${r.statusCode}")
                }
                .body(JsonNode::class.java)
                ?: throw AiUnavailableException("Ollama returned an empty body")
        } catch (e: RestClientException) {
            throw AiUnavailableException(
                "Could not reach Ollama at ${properties.baseUrl}. Is `ollama serve` running?", e,
            )
        }

        log.info(
            "Ollama {} produced {} token(s) in {}ms",
            properties.model,
            response.path("eval_count").asInt(0),
            response.path("total_duration").asLong(0) / 1_000_000,
        )

        val content = response.path("message").path("content").asText("")
        if (content.isBlank()) throw AiUnavailableException("Ollama returned no content")

        return runCatching { objectMapper.readTree(content) }
            .getOrElse {
                // Schema-constrained decoding should make this impossible; treat it as a provider
                // fault rather than something to repair by hand.
                throw AiUnavailableException("Ollama returned content that is not valid JSON")
            }
    }
}
