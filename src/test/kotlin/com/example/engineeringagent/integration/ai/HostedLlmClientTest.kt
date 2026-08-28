package com.example.engineeringagent.integration.ai

import com.example.engineeringagent.config.AiClientConfig
import com.example.engineeringagent.config.AiProperties
import com.example.engineeringagent.exception.AiUnavailableException
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hosted providers, which differ from Ollama in the way that matters most: the prompt leaves
 * the machine. These tests pin the request shape and the credential handling, because a silent
 * change to either is a security question, not just a bug.
 */
class HostedLlmClientTest {

    private lateinit var server: WireMockServer
    private val objectMapper = ObjectMapper()

    private val schema = objectMapper.readTree(
        """{"type":"object","properties":{"summary":{"type":"string"}},"required":["summary"],"additionalProperties":false}""",
    )

    @BeforeEach
    fun setUp() {
        server = WireMockServer(WireMockConfiguration.options().dynamicPort())
        server.start()
    }

    @AfterEach
    fun tearDown() = server.stop()

    private fun client(provider: String) = AiClientConfig().llmClient(
        AiProperties(
            enabled = true,
            provider = provider,
            baseUrl = server.baseUrl(),
            apiKey = "test-key",
            model = "test-model",
        ),
        objectMapper,
    )

    @Test
    fun `anthropic sends the api key as a header and reads the text block`() {
        server.stubFor(
            post(urlPathEqualTo("/v1/messages")).willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody(
                    """{"content":[{"type":"text","text":"{\"summary\":\"Upgrade merged.\"}"}],
                        "usage":{"input_tokens":10,"output_tokens":5}}""",
                ),
            ),
        )

        val result = client("anthropic").completeJson("rules", "evidence", schema)

        assertEquals("Upgrade merged.", result.path("summary").asText())
        server.verify(
            postRequestedFor(urlPathEqualTo("/v1/messages"))
                .withHeader("x-api-key", equalTo("test-key"))
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                // The schema must constrain generation, not merely be asked for politely.
                .withRequestBody(matchingJsonPath("$.output_config.format.type", equalTo("json_schema")))
                .withRequestBody(matchingJsonPath("$.output_config.format.schema")),
        )
    }

    @Test
    fun `openai sends a bearer token and nests the schema under json_schema`() {
        server.stubFor(
            post(urlPathEqualTo("/v1/chat/completions")).willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody(
                    """{"choices":[{"message":{"role":"assistant","content":"{\"summary\":\"Upgrade merged.\"}"}}],
                        "usage":{"prompt_tokens":10,"completion_tokens":5}}""",
                ),
            ),
        )

        val result = client("openai").completeJson("rules", "evidence", schema)

        assertEquals("Upgrade merged.", result.path("summary").asText())
        server.verify(
            postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer test-key"))
                .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_schema")))
                .withRequestBody(matchingJsonPath("$.response_format.json_schema.strict", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.response_format.json_schema.schema")),
        )
    }

    /** A refusal is an answer, not an outage, but it carries no summary — so it must not be parsed. */
    @Test
    fun `treats an openai refusal as unusable rather than as content`() {
        server.stubFor(
            post(urlPathEqualTo("/v1/chat/completions")).willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody(
                    """{"choices":[{"message":{"role":"assistant","refusal":"I cannot help with that","content":null}}]}""",
                ),
            ),
        )

        assertFailsWith<AiUnavailableException> { client("openai").completeJson("rules", "evidence", schema) }
    }

    /** An error body can echo the prompt, which is the ticket text. Only the status may be surfaced. */
    @Test
    fun `does not put the provider's error body into the exception message`() {
        server.stubFor(
            post(urlPathEqualTo("/v1/messages")).willReturn(
                aResponse().withStatus(400).withBody("""{"error":{"message":"bad request: ENG-185 refund flow"}}"""),
            ),
        )

        val error = assertFailsWith<AiUnavailableException> {
            client("anthropic").completeJson("rules", "evidence", schema)
        }

        assertTrue(error.message!!.contains("400"), error.message)
        assertFalse(error.message!!.contains("refund flow"), error.message)
    }

    @Test
    fun `reports hosted providers as not local so the security posture is visible`() {
        assertFalse(client("anthropic").isLocal)
        assertFalse(client("openai").isLocal)
    }

    @Test
    fun `refuses to start a hosted provider without an api key`() {
        val error = assertFailsWith<IllegalArgumentException> {
            AiClientConfig().llmClient(
                AiProperties(enabled = true, provider = "anthropic", apiKey = ""),
                objectMapper,
            )
        }
        assertTrue(error.message!!.contains("API key"), error.message)
    }

    @Test
    fun `rejects an unknown provider by name rather than falling back silently`() {
        val error = assertFailsWith<IllegalStateException> {
            AiClientConfig().llmClient(AiProperties(enabled = true, provider = "gemini"), objectMapper)
        }
        assertTrue(error.message!!.contains("gemini"), error.message)
    }
}
