package com.example.engineeringagent.integration.ai

import com.fasterxml.jackson.databind.JsonNode

/**
 * A language model that returns JSON conforming to a supplied schema.
 *
 * Kept deliberately narrow so the provider stays replaceable: the reasoning layer knows about
 * prompts and schemas, never about Ollama, OpenAI, or whatever comes next.
 */
interface LlmClient {

    /** Human-readable identifier of the backing model, for logging and provenance. */
    val model: String

    /** Whether the provider processes data outside this machine. Drives the security posture. */
    val isLocal: Boolean

    /**
     * Returns the model's response parsed as JSON.
     *
     * Implementations must request schema-constrained output where the provider supports it; the
     * caller still validates, because schema conformance is not the same as truthfulness.
     */
    fun completeJson(systemPrompt: String, userPrompt: String, schema: JsonNode): JsonNode
}
