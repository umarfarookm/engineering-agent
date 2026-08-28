package com.example.engineeringagent.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class SlackClientConfig {

    @Bean
    fun slackRestClient(properties: SlackProperties): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(Duration.ofSeconds(20))
        }
        return RestClient.builder()
            .baseUrl(properties.apiUrl)
            .requestFactory(factory)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${properties.botToken}")
            .build()
    }
}
