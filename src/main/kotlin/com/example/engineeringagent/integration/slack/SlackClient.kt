package com.example.engineeringagent.integration.slack

import com.example.engineeringagent.config.SlackProperties
import com.example.engineeringagent.exception.SlackNotConfiguredException
import com.example.engineeringagent.exception.SlackUnavailableException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * Posts messages to Slack.
 *
 * Slack's Web API answers `200 OK` with `{"ok": false, "error": "..."}` for application-level
 * failures — invalid auth, missing channel, bot not in the channel. Checking only the HTTP status
 * therefore reports success for a message that was never delivered, so every response is inspected.
 */
@Component
class SlackClient(
    private val slackRestClient: RestClient,
    private val properties: SlackProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun sendMessage(channel: String, text: String): SlackMessageResult {
        val response = post(
            "/chat.postMessage",
            objectMapper.createObjectNode()
                .put("channel", channel)
                .put("text", text)
                // Slack renders link previews for every URL otherwise, which buries a status
                // message under card after card.
                .put("unfurl_links", false)
                .put("unfurl_media", false),
        )

        val ts = response.path("ts").asText("")
        log.info("Posted status to {} (ts={})", channel, ts)
        return SlackMessageResult(channel = response.path("channel").asText(channel), timestamp = ts)
    }

    /** Opens (or reuses) a DM channel with [userId] and posts there. */
    fun sendDirectMessage(userId: String, text: String): SlackMessageResult {
        val opened = post("/conversations.open", objectMapper.createObjectNode().put("users", userId))
        val channel = opened.path("channel").path("id").asText("")
        if (channel.isBlank()) throw SlackUnavailableException("Slack did not return a DM channel for $userId")
        return sendMessage(channel, text)
    }

    private fun post(path: String, body: JsonNode): JsonNode {
        if (properties.botToken.isBlank()) {
            throw SlackNotConfiguredException("Slack is not configured. Set SLACK_BOT_TOKEN.")
        }

        val response = try {
            slackRestClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError) { _, r ->
                    throw SlackUnavailableException("Slack returned ${r.statusCode} for $path")
                }
                .body(JsonNode::class.java)
                ?: throw SlackUnavailableException("Slack returned an empty body for $path")
        } catch (e: RestClientException) {
            throw SlackUnavailableException("Could not reach Slack for $path", e)
        }

        if (!response.path("ok").asBoolean(false)) {
            val error = response.path("error").asText("unknown_error")
            throw SlackUnavailableException("Slack rejected $path: $error${explain(error)}")
        }
        return response
    }

    /** Slack's error codes are terse; the common ones have specific, actionable causes. */
    private fun explain(error: String): String = when (error) {
        "not_in_channel" -> " — invite the bot to the channel first"
        "channel_not_found" -> " — check SLACK_CHANNEL_ID, and that the bot can see the channel"
        "invalid_auth", "not_authed" -> " — check SLACK_BOT_TOKEN"
        "missing_scope" -> " — the bot token lacks a required scope (chat:write, im:write)"
        else -> ""
    }
}

data class SlackMessageResult(val channel: String, val timestamp: String)
