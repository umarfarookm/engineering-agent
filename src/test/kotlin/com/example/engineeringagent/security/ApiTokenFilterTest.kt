package com.example.engineeringagent.security

import com.example.engineeringagent.config.ApiExposureCheck
import com.example.engineeringagent.config.ApiSecurityProperties
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiTokenFilterTest {

    private val filter = ApiTokenFilter(ApiSecurityProperties(token = "secret-token"), ObjectMapper())

    private fun request(path: String, token: String? = null) =
        MockHttpServletRequest("GET", path).apply {
            requestURI = path
            token?.let { addHeader("Authorization", "Bearer $it") }
        }

    @Test
    fun `allows a request presenting the configured token`() {
        val chain = mock(FilterChain::class.java)
        val response = MockHttpServletResponse()

        filter.doFilter(request("/api/work/in-progress", "secret-token"), response, chain)

        verify(chain).doFilter(any(), any())
        assertEquals(200, response.status)
    }

    @Test
    fun `rejects a request with no token`() {
        val chain = mock(FilterChain::class.java)
        val response = MockHttpServletResponse()

        filter.doFilter(request("/api/work/in-progress"), response, chain)

        assertEquals(401, response.status)
        verify(chain, never()).doFilter(any(), any())
    }

    @Test
    fun `rejects a request with the wrong token`() {
        val chain = mock(FilterChain::class.java)
        val response = MockHttpServletResponse()

        filter.doFilter(request("/api/work/in-progress", "wrong"), response, chain)

        assertEquals(401, response.status)
        verify(chain, never()).doFilter(any(), any())
    }

    @Test
    fun `never echoes the presented credential in the response`() {
        val response = MockHttpServletResponse()

        filter.doFilter(request("/api/slack/send", "attackers-guess"), response, mock(FilterChain::class.java))

        assertFalse(response.contentAsString.contains("attackers-guess"), response.contentAsString)
    }

    @Test
    fun `leaves health open so a container can probe it without a credential`() {
        val chain = mock(FilterChain::class.java)
        val response = MockHttpServletResponse()

        filter.doFilter(request("/api/health"), response, chain)

        verify(chain).doFilter(any(), any())
        assertEquals(200, response.status)
    }

    @Test
    fun `is inactive when no token is configured`() {
        val open = ApiTokenFilter(ApiSecurityProperties(token = ""), ObjectMapper())
        val chain = mock(FilterChain::class.java)

        open.doFilter(request("/api/work/in-progress"), MockHttpServletResponse(), chain)

        verify(chain).doFilter(any(), any())
    }

    // --- startup guard ---

    @Test
    fun `refuses to start when bound beyond loopback without a token`() {
        // The mistake that turns a personal tool into an open endpoint which reads company tickets
        // and posts to Slack as you.
        val check = ApiExposureCheck(ApiSecurityProperties(token = ""), "0.0.0.0")

        val e = assertFailsWith<IllegalStateException> { check.verify() }

        assertTrue(e.message!!.contains("API_AUTH_TOKEN"), e.message!!)
    }

    @Test
    fun `starts when bound beyond loopback with a token`() {
        ApiExposureCheck(ApiSecurityProperties(token = "secret"), "0.0.0.0").verify()
    }

    @Test
    fun `starts unauthenticated when bound to loopback`() {
        ApiExposureCheck(ApiSecurityProperties(token = ""), "127.0.0.1").verify()
        ApiExposureCheck(ApiSecurityProperties(token = ""), "localhost").verify()
    }

    private fun <T> any(): T = org.mockito.ArgumentMatchers.any()
}
