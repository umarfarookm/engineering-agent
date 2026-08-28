package com.example.engineeringagent.service

import com.example.engineeringagent.agent.summary.DailyStatusFormatter
import com.example.engineeringagent.config.SlackProperties
import com.example.engineeringagent.exception.SlackNotConfiguredException
import com.example.engineeringagent.integration.slack.SlackClient
import com.example.engineeringagent.integration.slack.SlackMessageResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Preview-then-send, with a human in between.
 *
 * The two steps are deliberately separate calls, and [send] posts the text it is given rather than
 * regenerating it. Regenerating would mean the message that goes to the channel is not the one that
 * was approved — the model is non-deterministic and the underlying tickets move.
 */
@Service
class SlackService(
    private val summaryService: SummaryService,
    private val formatter: DailyStatusFormatter,
    private val slackClient: SlackClient,
    private val properties: SlackProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun preview(): SlackPreview {
        val report = summaryService.dailyReport()
        return SlackPreview(
            message = formatter.format(report),
            destination = destinationDescription(),
            ticketCount = report.summaries.size,
            notes = report.notes + report.summaries.flatMap { it.notes }.distinct(),
        )
    }

    fun send(text: String): SlackMessageResult {
        require(text.isNotBlank()) { "Refusing to send an empty message" }
        if (!properties.isConfigured()) {
            throw SlackNotConfiguredException(
                "Slack is not configured. Set SLACK_BOT_TOKEN and either SLACK_CHANNEL_ID or SLACK_USER_ID.",
            )
        }

        return when {
            properties.channelId.isNotBlank() -> slackClient.sendMessage(properties.channelId, text)
            else -> slackClient.sendDirectMessage(properties.userId, text)
        }.also { log.info("Daily status sent to {}", it.channel) }
    }

    private fun destinationDescription(): String = when {
        !properties.isConfigured() -> "not configured"
        properties.channelId.isNotBlank() -> "channel ${properties.channelId}"
        else -> "direct message to ${properties.userId}"
    }
}

data class SlackPreview(
    val message: String,
    val destination: String,
    val ticketCount: Int,
    /** Caveats for the human reviewing this, deliberately not included in the message itself. */
    val notes: List<String>,
)
