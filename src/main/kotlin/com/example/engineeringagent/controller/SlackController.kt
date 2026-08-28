package com.example.engineeringagent.controller

import com.example.engineeringagent.integration.slack.SlackMessageResult
import com.example.engineeringagent.service.SlackPreview
import com.example.engineeringagent.service.SlackService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/slack")
class SlackController(private val slackService: SlackService) {

    /** Builds the message without sending it. Nothing here reaches Slack. */
    @PostMapping("/preview")
    fun preview(): SlackPreview = slackService.preview()

    /**
     * Posts an already-approved message.
     *
     * The text is supplied by the caller rather than regenerated, so what reaches the channel is
     * exactly what a human read and approved.
     */
    @PostMapping("/send")
    fun send(@Valid @RequestBody request: SendMessageRequest): SlackMessageResult =
        slackService.send(request.message)
}

data class SendMessageRequest(
    @field:NotBlank(message = "message must not be blank")
    val message: String = "",
)
