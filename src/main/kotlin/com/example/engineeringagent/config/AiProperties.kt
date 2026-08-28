package com.example.engineeringagent.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ai")
data class AiProperties(
    /**
     * Master switch for AI processing. When false the system still produces a summary, built
     * deterministically from the evidence. See docs/SECURITY.md.
     */
    val enabled: Boolean = false,
    /** `ollama` (local), `anthropic`, or `openai`. See [AiProvider]. */
    val provider: String = "ollama",
    val model: String = "qwen2.5:7b-instruct",
    /** Blank uses the provider's own default endpoint. Set it to reach a compatible gateway. */
    val baseUrl: String = "",
    /** API key for a hosted provider. Never needed by, and never sent to, Ollama. */
    val apiKey: String = "",
    /** Local models on CPU are slow; a per-ticket call needs a generous ceiling. */
    val timeoutSeconds: Long = 1500,
    val temperature: Double = 0.0,
    /** Hosted providers bill by token and need an explicit ceiling. */
    val maxTokens: Int = 2000,
    /** One bounded retry on malformed output before falling back. */
    val maxAttempts: Int = 2,
) {
    fun resolvedProvider(): AiProvider =
        AiProvider.entries.firstOrNull { it.id.equals(provider.trim(), ignoreCase = true) }
            ?: throw IllegalStateException(
                "Unknown ai.provider '$provider'. Supported: ${AiProvider.entries.joinToString { it.id }}",
            )

    fun resolvedBaseUrl(): String = baseUrl.ifBlank { resolvedProvider().defaultBaseUrl }
}

/**
 * Where reasoning happens, and therefore where the ticket text goes.
 *
 * [local] is the security-relevant property, not a performance one: it decides whether a company's
 * ticket titles, branch names and file paths leave the machine at all.
 */
enum class AiProvider(val id: String, val defaultBaseUrl: String, val local: Boolean) {
    OLLAMA("ollama", "http://localhost:11434", local = true),
    ANTHROPIC("anthropic", "https://api.anthropic.com", local = false),
    OPENAI("openai", "https://api.openai.com", local = false),
}
