package com.example.engineeringagent.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bounds on how much evidence is carried into a context.
 *
 * These exist for two reasons at once: an LLM prompt has a finite budget, and every file path or
 * patch included is company data leaving the process. The caps therefore stay conservative.
 */
@ConfigurationProperties(prefix = "context")
data class ContextProperties(
    val maxFilesPerTicket: Int = 40,
    val maxCommitMessages: Int = 30,
    val maxPatchCharsPerFile: Int = 2_000,
    val maxTotalPatchChars: Int = 20_000,
)
