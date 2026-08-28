package com.example.engineeringagent.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration
import java.util.Base64

@Configuration
class JiraClientConfig {

    /**
     * Jira Cloud authenticates with HTTP Basic using the account email and an API token
     * (never a password). The header is built once here so no call site handles the credential.
     */
    @Bean
    fun jiraRestClient(properties: JiraProperties): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(Duration.ofSeconds(30))
        }

        val credentials = Base64.getEncoder()
            .encodeToString("${properties.email}:${properties.apiToken}".toByteArray())

        return RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestFactory(factory)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic $credentials")
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build()
    }
}
