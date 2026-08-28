package com.example.engineeringagent.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Refuses to serve an unauthenticated API to the network.
 *
 * Binding beyond loopback without a token is the single mistake that turns this from a personal
 * tool into an open endpoint that reads company tickets and posts to Slack as you. It is caught at
 * startup rather than left to be discovered.
 */
@Component
class ApiExposureCheck(
    private val securityProperties: ApiSecurityProperties,
    @Value("\${server.address:127.0.0.1}") private val bindAddress: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun verify() {
        val loopbackOnly = bindAddress in LOOPBACK

        if (!loopbackOnly && !securityProperties.isEnabled()) {
            throw IllegalStateException(
                "Refusing to start: the API is bound to $bindAddress, which is reachable from " +
                    "outside this machine, but API_AUTH_TOKEN is not set. Set a token, or bind to " +
                    "127.0.0.1.",
            )
        }

        if (loopbackOnly && !securityProperties.isEnabled()) {
            log.info("API is unauthenticated and bound to {} (localhost only)", bindAddress)
        } else {
            log.info("API requires a bearer token; bound to {}", bindAddress)
        }
    }

    private companion object {
        val LOOPBACK = setOf("127.0.0.1", "localhost", "::1")
    }
}
