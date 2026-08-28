package com.example.engineeringagent.exception

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(EngineeringAgentException::class)
    fun handle(e: EngineeringAgentException): ResponseEntity<ErrorResponse> {
        val status = when (e.state) {
            FailureState.JIRA_NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE
            FailureState.JIRA_UNAVAILABLE -> HttpStatus.BAD_GATEWAY
            FailureState.NO_ACTIVITY -> HttpStatus.NOT_FOUND
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }
        log.warn("Request failed with state {}: {}", e.state, e.message)
        return ResponseEntity.status(status)
            .body(ErrorResponse(state = e.state.name, message = e.message ?: "Request failed"))
    }
}

data class ErrorResponse(
    val state: String,
    val message: String,
    val timestamp: Instant = Instant.now(),
)
