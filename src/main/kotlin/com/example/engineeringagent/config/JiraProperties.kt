package com.example.engineeringagent.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "jira")
data class JiraProperties(
    /** e.g. https://your-company.atlassian.net */
    val baseUrl: String = "",
    val email: String = "",
    val apiToken: String = "",
    /** Jira statuses treated as "work I am currently doing". */
    val inProgressStatuses: List<String> = listOf("In Progress", "In Review", "Code Review"),
    /**
     * Custom field id holding acceptance criteria (e.g. "customfield_10001").
     * When null, acceptance criteria are parsed out of the description instead.
     */
    val acceptanceCriteriaField: String? = null,
    val maxResults: Int = 50,
) {
    fun isConfigured(): Boolean =
        baseUrl.isNotBlank() && email.isNotBlank() && apiToken.isNotBlank()
}
