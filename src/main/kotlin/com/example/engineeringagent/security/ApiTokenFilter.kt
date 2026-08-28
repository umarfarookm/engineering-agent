package com.example.engineeringagent.security

import com.example.engineeringagent.config.ApiSecurityProperties
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/**
 * Requires a shared secret on the API.
 *
 * The service reads a company's tickets and can post to Slack as you, so anything able to reach it
 * can speak in your name. Health is left open so a container orchestrator can probe it without a
 * credential; it reports only whether dependencies are configured.
 */
@Component
class ApiTokenFilter(
    private val properties: ApiSecurityProperties,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !properties.isEnabled() || request.requestURI == HEALTH_PATH

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val presented = request.getHeader(HttpHeaders.AUTHORIZATION)
            ?.removePrefix("Bearer ")
            ?.trim()
            .orEmpty()

        if (!matches(presented)) {
            // The path is safe to log; the presented credential is not.
            log.warn("Rejected unauthenticated request to {}", request.requestURI)
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write(
                objectMapper.writeValueAsString(
                    mapOf("error" to "unauthorized", "message" to "A valid bearer token is required."),
                ),
            )
            return
        }

        filterChain.doFilter(request, response)
    }

    /** Constant-time comparison: a timing side channel on a shared secret is still a side channel. */
    private fun matches(presented: String): Boolean =
        MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            properties.token.toByteArray(Charsets.UTF_8),
        )

    private companion object {
        const val HEALTH_PATH = "/api/health"
    }
}
