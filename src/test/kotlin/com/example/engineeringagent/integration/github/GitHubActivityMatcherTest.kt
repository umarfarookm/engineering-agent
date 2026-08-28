package com.example.engineeringagent.integration.github

import com.example.engineeringagent.config.GitHubClientConfig
import com.example.engineeringagent.config.GitHubProperties
import com.example.engineeringagent.domain.JiraComment
import com.example.engineeringagent.domain.JiraIssue
import com.example.engineeringagent.domain.LinkedIssue
import com.example.engineeringagent.domain.MatchConfidence
import com.example.engineeringagent.domain.MatchSignal
import com.example.engineeringagent.domain.PullRequestState
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubActivityMatcherTest {

    private lateinit var server: WireMockServer
    private lateinit var matcher: GitHubActivityMatcher
    private lateinit var client: GitHubClient

    @BeforeEach
    fun setUp() {
        server = WireMockServer(WireMockConfiguration.options().dynamicPort())
        server.start()
        val properties = GitHubProperties(
            token = "test-token",
            org = "acme",
            apiUrl = server.baseUrl(),
        )
        client = GitHubClient(GitHubClientConfig().gitHubRestClient(properties), properties)
        matcher = GitHubActivityMatcher(client, properties)
    }

    @AfterEach
    fun tearDown() = server.stop()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/github/$name")) { "missing fixture $name" }
            .bufferedReader().readText()

    private fun stub(path: String, body: String) {
        server.stubFor(
            get(urlPathEqualTo(path)).willReturn(
                aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body),
            ),
        )
    }

    private fun stubSearchFor(key: String, body: String) {
        server.stubFor(
            get(urlPathEqualTo("/search/issues"))
                .withQueryParam("q", containing(key))
                .willReturn(
                    aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body),
                ),
        )
    }

    private fun issue(
        key: String,
        description: String? = null,
        comments: List<String> = emptyList(),
        linked: List<LinkedIssue> = emptyList(),
    ) = JiraIssue(
        linkedIssues = linked,
        id = "1", key = key, summary = "s", description = description,
        status = "In Progress", statusCategory = null, assignee = null, priority = null,
        issueType = null, acceptanceCriteria = null, created = null, updated = null, url = null,
        comments = comments.mapIndexed { i, b -> JiraComment("$i", "someone", b, null, null) },
    )

    @Test
    fun `strategy 0 - follows a pull request url stated in the issue description`() {
        // ENG-80's description is nothing but a link to a PR under a different project's key.
        stub("/repos/acme/payments-service/pulls/50", fixture("pr-50.json"))
        stubSearchFor("ENG-80", fixture("search-empty.json"))
        stubSearchFor("PLT-3206", fixture("search-mb-3206.json"))

        val activity = matcher.findActivity(
            issue(
                "ENG-80",
                "As discussed in PR for ticket PLT-3206: " +
                    "https://github.com/acme/payments-service/pull/50#discussion_r2180211526",
            ),
        )

        assertEquals(MatchConfidence.MATCHED, activity.confidence)
        val match = activity.matches.single()
        assertEquals(50, match.pullRequest.number)
        assertTrue(MatchSignal.EXPLICIT_PR_URL_IN_ISSUE in match.signals)
        assertEquals(PullRequestState.MERGED, match.pullRequest.state)
    }

    @Test
    fun `searches cross-project keys found in the issue text, not only its own key`() {
        stub("/repos/acme/payments-service/pulls/50", fixture("pr-50.json"))
        stubSearchFor("ENG-80", fixture("search-empty.json"))
        stubSearchFor("PLT-3206", fixture("search-mb-3206.json"))

        val activity = matcher.findActivity(
            issue("ENG-80", "As discussed in PR for ticket PLT-3206: https://github.com/acme/payments-service/pull/50"),
        )

        assertEquals(listOf("ENG-80", "PLT-3206"), activity.searchedKeys)
        server.verify(getRequestedFor(urlPathEqualTo("/search/issues")).withQueryParam("q", containing("PLT-3206")))
    }

    @Test
    fun `matches a branch whose title lost the key to GitHub's auto-generated casing`() {
        // PR 50's title is "Feature/plt 3206 ..."; only the branch carries the real key.
        stub("/repos/acme/payments-service/pulls/50", fixture("pr-50.json"))
        stubSearchFor("PLT-3206", fixture("search-mb-3206.json"))

        val match = matcher.findActivity(issue("PLT-3206")).matches.single()

        assertEquals(MatchConfidence.MATCHED, match.confidence)
        assertTrue(MatchSignal.TICKET_KEY_IN_BRANCH in match.signals)
        assertTrue(MatchSignal.TICKET_KEY_IN_PR_TITLE in match.signals, "lenient title match should also fire")
    }

    @Test
    fun `matches a conventional branch and title`() {
        stub("/repos/acme/billing-service/pulls/28", fixture("pr-28.json"))
        stubSearchFor("ENG-185", fixture("search-den-185.json"))

        val activity = matcher.findActivity(issue("ENG-185"))

        val match = activity.matches.single()
        assertEquals(MatchConfidence.MATCHED, match.confidence)
        assertEquals("feature/ENG-185", match.pullRequest.branch)
        assertEquals(PullRequestState.OPEN, match.pullRequest.state)
        assertEquals("ENG-185", match.matchedVia)
    }

    @Test
    fun `reports NO_MATCH when a ticket genuinely has no pull request`() {
        // ENG-267 returns zero org-wide results in reality; that must not become a false link.
        stubSearchFor("ENG-267", fixture("search-empty.json"))

        val activity = matcher.findActivity(issue("ENG-267"))

        assertEquals(MatchConfidence.NO_MATCH, activity.confidence)
        assertTrue(activity.matches.isEmpty())
        assertNull(activity.unavailableReason, "no match is not the same as GitHub being unavailable")
    }

    @Test
    fun `a search hit the key cannot be confirmed against is a POSSIBLE_MATCH`() {
        // GitHub's search also indexes PR comments, which we never fetch. So a search can return a
        // pull request whose branch, title and body contain no reference to the ticket at all.
        // That is a lead, not evidence, and must not be promoted.
        stub("/repos/acme/platform-monorepo/pulls/488", fixture("pr-488.json"))
        stubSearchFor("ENG-267", fixture("search-unrelated-hit.json"))

        val activity = matcher.findActivity(issue("ENG-267"))

        assertEquals(MatchConfidence.POSSIBLE_MATCH, activity.confidence)
        assertEquals(listOf(MatchSignal.UNCONFIRMED_SEARCH_HIT), activity.matches.single().signals)
    }

    @Test
    fun `ignores repositories outside the configured org`() {
        val properties = GitHubProperties(token = "t", org = "someone-else", apiUrl = server.baseUrl())
        val scoped = GitHubActivityMatcher(
            GitHubClient(GitHubClientConfig().gitHubRestClient(properties), properties), properties,
        )
        stub("/repos/acme/billing-service/pulls/28", fixture("pr-28.json"))
        stubSearchFor("ENG-185", fixture("search-den-185.json"))

        assertEquals(MatchConfidence.NO_MATCH, scoped.findActivity(issue("ENG-185")).confidence)
    }

    @Test
    fun `collapses a pull request found by both an explicit url and a search`() {
        stub("/repos/acme/payments-service/pulls/50", fixture("pr-50.json"))
        stubSearchFor("PLT-3206", fixture("search-mb-3206.json"))

        val activity = matcher.findActivity(
            issue("PLT-3206", "https://github.com/acme/payments-service/pull/50"),
        )

        assertEquals(1, activity.matches.size, "the same PR must not be reported twice")
        val signals = activity.matches.single().signals
        assertTrue(MatchSignal.EXPLICIT_PR_URL_IN_ISSUE in signals)
        assertTrue(MatchSignal.TICKET_KEY_IN_BRANCH in signals)
    }

    @Test
    fun `finds a pull request linked from a comment rather than the description`() {
        stub("/repos/acme/payments-service/pulls/50", fixture("pr-50.json"))
        stubSearchFor("ENG-99", fixture("search-empty.json"))

        val activity = matcher.findActivity(
            issue("ENG-99", description = null, comments = listOf("fixed in https://github.com/acme/payments-service/pull/50")),
        )

        assertEquals(MatchConfidence.MATCHED, activity.confidence)
    }

    @Test
    fun `reports GitHub being unavailable distinctly from finding nothing`() {
        server.stubFor(
            get(urlPathEqualTo("/search/issues")).willReturn(aResponse().withStatus(503)),
        )

        val activity = matcher.findActivity(issue("ENG-267"))

        assertEquals(MatchConfidence.NO_MATCH, activity.confidence)
        assertNotNull(activity.unavailableReason, "callers must be able to tell unknown from none")
    }

    @Test
    fun `surfaces an exhausted rate limit rather than reporting no activity`() {
        server.stubFor(
            get(urlPathEqualTo("/search/issues")).willReturn(
                aResponse().withStatus(403).withHeader("x-ratelimit-remaining", "0"),
            ),
        )

        val activity = matcher.findActivity(issue("ENG-267"))

        assertTrue(activity.unavailableReason!!.contains("rate limit"), activity.unavailableReason!!)
    }

    @Test
    fun `omits patch content unless diffs are explicitly enabled`() {
        stub("/repos/acme/payments-service/pulls/50/files", fixture("pr-50-files.json"))

        val files = client.getChangedFiles("acme", "payments-service", 50)

        assertEquals("src/main/kotlin/reports/EmailRetryPolicy.kt", files.single().path)
        assertEquals(120, files.single().additions)
        assertNull(files.single().patch, "diffs must not leave the process by default")
    }

    @Test
    fun `includes patch content when diffs are enabled`() {
        val properties = GitHubProperties(token = "t", org = "acme", apiUrl = server.baseUrl(), includeDiffs = true)
        val withDiffs = GitHubClient(GitHubClientConfig().gitHubRestClient(properties), properties)
        stub("/repos/acme/payments-service/pulls/50/files", fixture("pr-50-files.json"))

        assertNotNull(withDiffs.getChangedFiles("acme", "payments-service", 50).single().patch)
    }

    @Test
    fun `reads commits and reviews`() {
        stub("/repos/acme/payments-service/pulls/50/commits", fixture("pr-50-commits.json"))
        stub("/repos/acme/payments-service/pulls/50/reviews", fixture("pr-50-reviews.json"))

        val commits = client.getPullRequestCommits("acme", "payments-service", 50)
        val reviews = client.getReviews("acme", "payments-service", 50)

        assertEquals("PLT-3206: add retry policy for report emails", commits.single().message)
        assertEquals("Alex Rivera", commits.single().author)
        assertEquals("CHANGES_REQUESTED", reviews.single().state)
    }

    @Test
    fun `work found only through a linked issue stays a POSSIBLE_MATCH`() {
        // ENG-267 is cloned from ENG-143. A pull request on the clone is somebody's work, but that
        // it is *this* ticket's work is exactly the unproven part — however clean its branch name.
        stub("/repos/acme/billing-service/pulls/28", fixture("pr-28.json"))
        stubSearchFor("ENG-267", fixture("search-empty.json"))
        stubSearchFor("ENG-185", fixture("search-den-185.json"))

        val activity = matcher.findActivity(
            issue("ENG-267", linked = listOf(LinkedIssue("ENG-185", "cloned ticket", "In Progress", "clones"))),
        )

        assertEquals(MatchConfidence.POSSIBLE_MATCH, activity.confidence)
        val match = activity.matches.single()
        assertTrue(MatchSignal.VIA_LINKED_ISSUE in match.signals)
        assertTrue(MatchSignal.TICKET_KEY_IN_BRANCH in match.signals, "the branch evidence is still real")
        assertEquals("ENG-185", match.matchedVia, "the reader must be able to see where this came from")
    }

    @Test
    fun `a key the issue text points at is treated as this ticket's own work`() {
        // Contrast with the linked-issue case: ENG-80 says outright that PLT-3206 is its work.
        stub("/repos/acme/payments-service/pulls/50", fixture("pr-50.json"))
        stubSearchFor("ENG-80", fixture("search-empty.json"))
        stubSearchFor("PLT-3206", fixture("search-mb-3206.json"))

        val activity = matcher.findActivity(issue("ENG-80", "As discussed in PR for ticket PLT-3206"))

        assertEquals(MatchConfidence.MATCHED, activity.confidence)
        assertTrue(MatchSignal.VIA_LINKED_ISSUE !in activity.matches.single().signals)
    }
}
