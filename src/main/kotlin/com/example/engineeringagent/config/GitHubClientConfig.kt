package com.example.engineeringagent.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class GitHubClientConfig {

    @Bean
    fun gitHubRestClient(properties: GitHubProperties): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(Duration.ofSeconds(30))
        }

        return RestClient.builder()
            .baseUrl(properties.apiUrl)
            .requestFactory(factory)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${properties.token}")
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
            // Pinning the API version keeps a future default change from silently altering payloads.
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .build()
    }
}
