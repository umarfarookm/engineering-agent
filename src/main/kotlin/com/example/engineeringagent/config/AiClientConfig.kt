package com.example.engineeringagent.config

import com.example.engineeringagent.integration.ai.AnthropicLlmClient
import com.example.engineeringagent.integration.ai.LlmClient
import com.example.engineeringagent.integration.ai.OllamaLlmClient
import com.example.engineeringagent.integration.ai.OpenAiLlmClient
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * Chooses the reasoning provider.
 *
 * Exactly one [LlmClient] exists, picked from configuration, so the reasoning layer never learns
 * which provider it is talking to — the point of the abstraction. Swapping local for hosted is a
 * configuration change, not a code change.
 */
@Configuration
class AiClientConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun llmClient(properties: AiProperties, objectMapper: ObjectMapper): LlmClient {
        val provider = properties.resolvedProvider()
        val restClient = restClient(properties, provider)

        if (properties.enabled && !provider.local) {
            require(properties.apiKey.isNotBlank()) {
                "ai.provider is '${provider.id}' but no API key is set. " +
                    "Set AI_API_KEY, or use AI_PROVIDER=ollama to keep reasoning on this machine."
            }
            // A person turning this on deserves to see, in the log, that it changed where the
            // company's ticket text goes. Silence here would be the wrong default.
            log.warn(
                "AI provider is {} ({}): ticket text and code metadata will be sent off this " +
                    "machine. See docs/SECURITY.md.",
                provider.id, properties.resolvedBaseUrl(),
            )
        } else if (properties.enabled) {
            log.info("AI provider is {} at {}; nothing leaves this machine.", provider.id, properties.resolvedBaseUrl())
        }

        return when (provider) {
            AiProvider.OLLAMA -> OllamaLlmClient(restClient, properties, objectMapper)
            AiProvider.ANTHROPIC -> AnthropicLlmClient(restClient, properties, objectMapper)
            AiProvider.OPENAI -> OpenAiLlmClient(restClient, properties, objectMapper)
        }
    }

    private fun restClient(properties: AiProperties, provider: AiProvider): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            // Local models on CPU are slow; a premature timeout looks like an outage.
            setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds))
        }

        return RestClient.builder()
            .baseUrl(properties.resolvedBaseUrl())
            .requestFactory(factory)
            .apply {
                when (provider) {
                    AiProvider.OLLAMA -> Unit
                    AiProvider.ANTHROPIC -> it.defaultHeaders { headers ->
                        headers.set("x-api-key", properties.apiKey)
                        headers.set("anthropic-version", ANTHROPIC_VERSION)
                    }
                    AiProvider.OPENAI -> it.defaultHeaders { headers ->
                        headers.setBearerAuth(properties.apiKey)
                    }
                }
            }
            .build()
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
