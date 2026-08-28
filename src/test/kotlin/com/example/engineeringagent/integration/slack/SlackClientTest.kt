package com.example.engineeringagent.integration.slack

import com.example.engineeringagent.config.SlackClientConfig
import com.example.engineeringagent.config.SlackProperties
import com.example.engineeringagent.exception.SlackNotConfiguredException
import com.example.engineeringagent.exception.SlackUnavailableException
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.matching.ContainsPattern
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SlackClientTest {

    private lateinit var server: WireMockServer
    private lateinit var client: SlackClient

    @BeforeEach
    fun setUp() {
        server = WireMockServer(WireMockConfiguration.options().dynamicPort())
        server.start()
        val properties = SlackProperties(
            botToken = "xoxb-test", apiUrl = server.baseUrl(), channelId = "C123",
        )
        client = SlackClient(SlackClientConfig().slackRestClient(properties), properties, ObjectMapper())
    }

    @AfterEach
    fun tearDown() = server.stop()

    private fun stub(path: String, body: String, status: Int = 200) {
        server.stubFor(
            post(urlPathEqualTo(path)).willReturn(
                aResponse().withStatus(status)
                    .withHeader("Content-Type", "application/json").withBody(body),
            ),
        )
    }

    @Test
    fun `posts a message and returns its timestamp`() {
        stub("/chat.postMessage", """{"ok":true,"channel":"C123","ts":"1724580000.000100"}""")

        val result = client.sendMessage("C123", "*Daily status*")

        assertEquals("C123", result.channel)
        assertEquals("1724580000.000100", result.timestamp)
    }

    @Test
    fun `treats ok false as a failure despite the 200 status`() {
        // Slack signals application errors in the body, not the status line. Trusting the status
        // reports success for a message nobody received.
        stub("/chat.postMessage", """{"ok":false,"error":"channel_not_found"}""")

        val e = assertFailsWith<SlackUnavailableException> { client.sendMessage("C123", "hello") }

        assertTrue(e.message!!.contains("channel_not_found"), e.message!!)
    }

    @Test
    fun `explains the common Slack errors instead of echoing a code`() {
        stub("/chat.postMessage", """{"ok":false,"error":"not_in_channel"}""")

        val e = assertFailsWith<SlackUnavailableException> { client.sendMessage("C123", "hello") }

        assertTrue(e.message!!.contains("invite the bot"), e.message!!)
    }

    @Test
    fun `explains a missing scope`() {
        stub("/chat.postMessage", """{"ok":false,"error":"missing_scope"}""")

        val e = assertFailsWith<SlackUnavailableException> { client.sendMessage("C123", "hello") }

        assertTrue(e.message!!.contains("chat:write"), e.message!!)
    }

    @Test
    fun `sends the bot token as a bearer credential`() {
        stub("/chat.postMessage", """{"ok":true,"channel":"C123","ts":"1"}""")

        client.sendMessage("C123", "hello")

        server.verify(
            postRequestedFor(urlPathEqualTo("/chat.postMessage"))
                .withHeader("Authorization", matching("Bearer xoxb-.*")),
        )
    }

    @Test
    fun `disables unfurling so the status is not buried under link previews`() {
        stub("/chat.postMessage", """{"ok":true,"channel":"C123","ts":"1"}""")

        client.sendMessage("C123", "see https://github.com/acme/repo/pull/50")

        server.verify(
            postRequestedFor(urlPathEqualTo("/chat.postMessage"))
                .withRequestBody(ContainsPattern(""""unfurl_links":false""")),
        )
    }

    @Test
    fun `opens a conversation before sending a direct message`() {
        stub("/conversations.open", """{"ok":true,"channel":{"id":"D999"}}""")
        stub("/chat.postMessage", """{"ok":true,"channel":"D999","ts":"2"}""")

        val result = client.sendDirectMessage("U123", "hello")

        assertEquals("D999", result.channel)
        server.verify(postRequestedFor(urlPathEqualTo("/conversations.open")))
    }

    @Test
    fun `fails clearly when a direct message channel cannot be opened`() {
        stub("/conversations.open", """{"ok":true,"channel":{}}""")

        assertFailsWith<SlackUnavailableException> { client.sendDirectMessage("U123", "hello") }
    }

    @Test
    fun `surfaces a transport failure`() {
        server.stop()

        assertFailsWith<SlackUnavailableException> { client.sendMessage("C123", "hello") }
    }

    @Test
    fun `refuses to call Slack with no token`() {
        val properties = SlackProperties(botToken = "", apiUrl = server.baseUrl())
        val bare = SlackClient(SlackClientConfig().slackRestClient(properties), properties, ObjectMapper())

        assertFailsWith<SlackNotConfiguredException> { bare.sendMessage("C123", "hello") }
    }
}
