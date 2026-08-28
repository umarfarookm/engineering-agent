package com.example.engineeringagent.integration.ai

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/**
 * Loads prompt templates from resources.
 *
 * Prompts live in files rather than string literals so they can be reviewed as prose, diffed
 * meaningfully, and changed without touching service code.
 */
@Component
class PromptLoader {

    private val cache = mutableMapOf<String, String>()

    fun load(name: String): String = cache.getOrPut(name) {
        val resource = ClassPathResource("prompts/$name")
        require(resource.exists()) { "Prompt not found: prompts/$name" }
        resource.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
    }
}
