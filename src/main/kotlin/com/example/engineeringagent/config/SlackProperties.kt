package com.example.engineeringagent.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "slack")
data class SlackProperties(
    val botToken: String = "",
    val apiUrl: String = "https://slack.com/api",
    /** Default destination for the daily status. */
    val channelId: String = "",
    /** Slack user id for direct-message mode — safer than a team channel while trust is being built. */
    val userId: String = "",
) {
    fun isConfigured(): Boolean = botToken.isNotBlank() && (channelId.isNotBlank() || userId.isNotBlank())
}
