package com.example.engineeringagent.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class AiClientConfig {

    @Bean
    fun ollamaRestClient(properties: AiProperties): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            // Local models on CPU are slow; a premature timeout looks like an outage.
            setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds))
        }
        return RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestFactory(factory)
            .build()
    }
}
