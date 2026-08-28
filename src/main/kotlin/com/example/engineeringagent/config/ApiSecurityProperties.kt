package com.example.engineeringagent.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "api.security")
data class ApiSecurityProperties(
    /**
     * Shared secret required on every API call except health.
     *
     * Empty is permitted only while the service is bound to loopback. Once it is reachable from
     * anywhere else — an n8n container counts — startup fails without one.
     */
    val token: String = "",
) {
    fun isEnabled(): Boolean = token.isNotBlank()
}
