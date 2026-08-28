package com.example.engineeringagent.integration.jira

import com.example.engineeringagent.config.JiraClientConfig
import com.example.engineeringagent.config.JiraProperties
import com.example.engineeringagent.exception.JiraNotConfiguredException
import com.example.engineeringagent.exception.JiraUnavailableException
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the client against recorded fixtures served by WireMock.
 * No test in this suite may talk to a real Jira instance.
 */
class JiraClientTest {

    private lateinit var server: WireMockServer
    private lateinit var client: JiraClient
    private lateinit var properties: JiraProperties

    @BeforeEach
    fun setUp() {
        server = WireMockServer(WireMockConfiguration.options().dynamicPort())
        server.start()
        properties = JiraProperties(
            baseUrl = server.baseUrl(),
            email = "alex.rivera@example.com",
            apiToken = "test-token",
            inProgressStatuses = listOf("In Progress", "In Review", "Code Review"),
        )
        client = JiraClient(JiraClientConfig().jiraRestClient(properties), properties, JiraIssueMapper())
    }

    @AfterEach
    fun tearDown() = server.stop()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/jira/$name")) { "missing fixture $name" }
            .bufferedReader().readText()

    private fun stubJson(path: String, body: String) {
        server.stubFor(
            get(urlPathEqualTo(path)).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body),
            ),
        )
    }

    @Test
    fun `resolves the authenticated account instead of hard-coding a user`() {
        stubJson("/rest/api/3/myself", fixture("myself.json"))

        val user = client.getCurrentUser()

        assertEquals("Alex Rivera", user.displayName)
        assertEquals("5b10a2844c20165700ede21g", user.accountId)
    }

    @Test
    fun `sends basic auth built from email and api token`() {
        stubJson("/rest/api/3/myself", fixture("myself.json"))

        client.getCurrentUser()

        server.verify(
            getRequestedFor(urlPathEqualTo("/rest/api/3/myself"))
                .withHeader("Authorization", matching("Basic .+")),
        )
    }

    @Test
    fun `queries the current search endpoint with an explicit field list`() {
        // The /search/jql endpoint returns no fields unless they are named explicitly, so an
        // omitted `fields` parameter silently produces issues with no data.
        stubJson("/rest/api/3/search/jql", fixture("search-jql-page2.json"))

        client.getInProgressIssues()

        server.verify(
            getRequestedFor(urlPathEqualTo("/rest/api/3/search/jql"))
                .withQueryParam("fields", matching(".*summary.*"))
                .withQueryParam("fields", matching(".*status.*"))
                .withQueryParam("fields", matching(".*issuelinks.*")),
        )
    }

    @Test
    fun `builds JQL from configured statuses without hard-coding the assignee`() {
        stubJson("/rest/api/3/search/jql", fixture("search-jql-page2.json"))

        client.getInProgressIssues()

        server.verify(
            getRequestedFor(urlPathEqualTo("/rest/api/3/search/jql"))
                .withQueryParam(
                    "jql",
                    equalTo(
                        "assignee = currentUser() AND status IN (\"In Progress\", \"In Review\", " +
                            "\"Code Review\") ORDER BY updated DESC",
                    ),
                ),
        )
    }

    @Test
    fun `follows nextPageToken cursors and stops when absent`() {
        server.stubFor(
            get(urlPathEqualTo("/rest/api/3/search/jql"))
                .withQueryParam("nextPageToken", absent())
                .willReturn(
                    aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture("search-jql-page1.json")),
                ),
        )
        server.stubFor(
            get(urlPathEqualTo("/rest/api/3/search/jql"))
                .withQueryParam("nextPageToken", equalTo("CURSOR_PAGE_2"))
                .willReturn(
                    aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture("search-jql-page2.json")),
                ),
        )

        val issues = client.getInProgressIssues()

        assertEquals(listOf("ENG-267", "ENG-301"), issues.map { it.key })
    }

    @Test
    fun `normalizes an issue including flattened ADF and linked issues`() {
        stubJson("/rest/api/3/search/jql", fixture("search-jql-page1.json"))
        server.stubFor(
            get(urlPathEqualTo("/rest/api/3/search/jql"))
                .withQueryParam("nextPageToken", equalTo("CURSOR_PAGE_2"))
                .willReturn(
                    aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"issues":[]}"""),
                ),
        )

        val issue = client.getInProgressIssues().first { it.key == "ENG-267" }

        assertEquals("Improve Mapbox search behaviour", issue.summary)
        assertEquals("In Progress", issue.status)
        assertEquals("High", issue.priority)
        assertEquals(listOf("search", "frontend"), issue.labels)
        assertEquals("Alex Rivera", issue.assignee?.displayName)
        assertTrue(issue.description!!.contains("duplicate and irrelevant"), issue.description!!)
        assertEquals("${server.baseUrl()}/browse/ENG-267", issue.url)

        val link = issue.linkedIssues.single()
        assertEquals("ENG-260", link.key)
        // inwardIssue populated means this issue is blocked BY the other one.
        assertEquals("is blocked by", link.relationship)
    }

    @Test
    fun `extracts acceptance criteria from its heading and stops at the next heading`() {
        stubJson("/rest/api/3/search/jql", fixture("search-jql-page1.json"))
        server.stubFor(
            get(urlPathEqualTo("/rest/api/3/search/jql"))
                .withQueryParam("nextPageToken", equalTo("CURSOR_PAGE_2"))
                .willReturn(
                    aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"issues":[]}"""),
                ),
        )

        val criteria = client.getInProgressIssues().first { it.key == "ENG-267" }.acceptanceCriteria!!

        assertTrue(criteria.contains("Results filtered by country"), criteria)
        assertTrue(criteria.contains("No duplicate results"), criteria)
        assertTrue(!criteria.contains("platform team"), "must stop at the following heading: $criteria")
    }

    @Test
    fun `missing fields become null rather than invented defaults`() {
        stubJson("/rest/api/3/search/jql", fixture("search-jql-page2.json"))

        val issue = client.getInProgressIssues().single()

        assertNull(issue.assignee)
        assertNull(issue.priority)
        assertNull(issue.description)
        assertNull(issue.acceptanceCriteria)
        assertTrue(issue.labels.isEmpty())
    }

    @Test
    fun `surfaces a Jira outage as JIRA_UNAVAILABLE without leaking the response body`() {
        server.stubFor(
            get(urlPathEqualTo("/rest/api/3/search/jql")).willReturn(
                aResponse().withStatus(500).withBody("""{"secret":"do-not-leak"}"""),
            ),
        )

        val e = assertFailsWith<JiraUnavailableException> { client.getInProgressIssues() }

        assertTrue(!e.message!!.contains("do-not-leak"), e.message!!)
    }

    @Test
    fun `treats the removed search endpoint returning 410 Gone as an outage`() {
        server.stubFor(
            get(urlPathEqualTo("/rest/api/3/search/jql")).willReturn(aResponse().withStatus(410)),
        )

        assertFailsWith<JiraUnavailableException> { client.getInProgressIssues() }
    }

    @Test
    fun `fails clearly when credentials are absent instead of calling out`() {
        val unconfigured = JiraProperties(baseUrl = "", email = "", apiToken = "")
        val bare = JiraClient(
            JiraClientConfig().jiraRestClient(unconfigured), unconfigured, JiraIssueMapper(),
        )

        assertFailsWith<JiraNotConfiguredException> { bare.getCurrentUser() }
    }

    @Test
    fun `reads comments and flattens their ADF bodies`() {
        stubJson("/rest/api/3/issue/ENG-267/comment", fixture("issue-den-267-with-comments.json"))

        val comments = client.getIssueComments("ENG-267")

        assertEquals(1, comments.size)
        assertEquals("Priya Raman", comments.single().author)
        assertTrue(comments.single().body.contains("Blocked on the API key rotation"))
        assertTrue(comments.single().body.contains("https://example.com/ticket/9"))
    }
}
