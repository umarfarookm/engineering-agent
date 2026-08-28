package com.example.engineeringagent.controller

import com.example.engineeringagent.config.GitHubProperties
import com.example.engineeringagent.config.JiraProperties
import com.example.engineeringagent.config.SlackProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController(
    private val jiraProperties: JiraProperties,
    private val gitHubProperties: GitHubProperties,
    private val slackProperties: SlackProperties,
) {

    /**
     * Reports whether dependencies are configured — never their values, and never enough detail to
     * be useful to someone who should not be calling this.
     */
    @GetMapping("/api/health")
    fun health(): Map<String, Any> = mapOf(
        "status" to "UP",
        "dependencies" to mapOf(
            "jira" to if (jiraProperties.isConfigured()) "CONFIGURED" else "NOT_CONFIGURED",
            "github" to if (gitHubProperties.isConfigured()) "CONFIGURED" else "NOT_CONFIGURED",
            "slack" to if (slackProperties.isConfigured()) "CONFIGURED" else "NOT_CONFIGURED",
        ),
    )
}
