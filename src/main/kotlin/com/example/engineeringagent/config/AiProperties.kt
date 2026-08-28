package com.example.engineeringagent.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ai")
data class AiProperties(
    /**
     * Master switch for AI processing. When false the system still produces a summary, built
     * deterministically from the evidence. See docs/SECURITY.md.
     */
    val enabled: Boolean = false,
    val provider: String = "ollama",
    val model: String = "llama3.2:latest",
    val baseUrl: String = "http://localhost:11434",
    /** Small local models are slow; a per-ticket call needs a generous ceiling. */
    val timeoutSeconds: Long = 300,
    val temperature: Double = 0.0,
    /** One bounded retry on malformed output before falling back. */
    val maxAttempts: Int = 2,
)
