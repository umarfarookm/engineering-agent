package com.example.engineeringagent.integration.ai

import com.example.engineeringagent.config.AiClientConfig
import com.example.engineeringagent.config.AiProperties
import com.example.engineeringagent.exception.AiUnavailableException
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
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

class OllamaLlmClientTest {

    private lateinit var server: WireMockServer
    private lateinit var client: OllamaLlmClient
    private val objectMapper = ObjectMapper()

    private val schema = objectMapper.readTree(
        """{"type":"object","properties":{"summary":{"type":"string"}},"required":["summary"]}""",
    )

    @BeforeEach
    fun setUp() {
        server = WireMockServer(WireMockConfiguration.options().dynamicPort())
        server.start()
        val properties = AiProperties(enabled = true, baseUrl = server.baseUrl(), model = "llama3.2:latest")
        // Built through the real factory, so provider selection is exercised too.
        client = AiClientConfig().llmClient(properties, objectMapper) as OllamaLlmClient
    }

    @AfterEach
    fun tearDown() = server.stop()

    private fun respondWith(content: String) {
        server.stubFor(
            post(urlPathEqualTo("/api/chat")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody(
                        objectMapper.createObjectNode().apply {
                            set<com.fasterxml.jackson.databind.JsonNode>(
                                "message",
                                objectMapper.createObjectNode().put("role", "assistant").put("content", content),
                            )
                            put("eval_count", 42)
                            put("total_duration", 5_000_000_000L)
                        }.toString(),
                    ),
            ),
        )
    }

    @Test
    fun `parses a schema-constrained response`() {
        respondWith("""{"summary":"Upgraded the service."}""")

        val result = client.completeJson("system", "user", schema)

        assertEquals("Upgraded the service.", result.path("summary").asText())
    }

    @Test
    fun `sends the schema so decoding is constrained rather than merely requested`() {
        respondWith("""{"summary":"x"}""")

        client.completeJson("system", "user", schema)

        server.verify(
            postRequestedFor(urlPathEqualTo("/api/chat"))
                .withRequestBody(ContainsPattern(""""format""""))
                .withRequestBody(ContainsPattern(""""stream":false""")),
        )
    }

    @Test
    fun `sends system and user prompts as separate messages`() {
        respondWith("""{"summary":"x"}""")

        client.completeJson("SYSTEM RULES", "TICKET EVIDENCE", schema)

        server.verify(
            postRequestedFor(urlPathEqualTo("/api/chat"))
                .withRequestBody(ContainsPattern("SYSTEM RULES"))
                .withRequestBody(ContainsPattern("TICKET EVIDENCE")),
        )
    }

    @Test
    fun `uses a deterministic temperature`() {
        respondWith("""{"summary":"x"}""")

        client.completeJson("s", "u", schema)

        server.verify(
            postRequestedFor(urlPathEqualTo("/api/chat"))
                .withRequestBody(equalToJson("""{"options":{"temperature":0.0}}""", true, true)),
        )
    }

    @Test
    fun `reports content that is not JSON as an AI failure`() {
        respondWith("I'd be happy to help you with that!")

        assertFailsWith<AiUnavailableException> { client.completeJson("s", "u", schema) }
    }

    @Test
    fun `reports empty content as an AI failure`() {
        respondWith("")

        assertFailsWith<AiUnavailableException> { client.completeJson("s", "u", schema) }
    }

    @Test
    fun `explains how to start Ollama when it is not running`() {
        server.stop()

        val e = assertFailsWith<AiUnavailableException> { client.completeJson("s", "u", schema) }

        assertTrue(e.message!!.contains("ollama serve"), e.message!!)
    }

    @Test
    fun `surfaces an error status`() {
        server.stubFor(post(urlPathEqualTo("/api/chat")).willReturn(aResponse().withStatus(500)))

        assertFailsWith<AiUnavailableException> { client.completeJson("s", "u", schema) }
    }

    @Test
    fun `declares itself local so callers can reason about data flow`() {
        assertTrue(client.isLocal)
    }
}
