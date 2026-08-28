package com.example.engineeringagent.agent.reasoning

import com.example.engineeringagent.domain.ActivityMatch
import com.example.engineeringagent.domain.CodeChangeSummary
import com.example.engineeringagent.domain.EngineeringContext
import com.example.engineeringagent.domain.GitHubPullRequest
import com.example.engineeringagent.domain.JiraIssue
import com.example.engineeringagent.domain.MatchConfidence
import com.example.engineeringagent.domain.MatchSignal
import com.example.engineeringagent.domain.PullRequestReview
import com.example.engineeringagent.domain.PullRequestState
import com.example.engineeringagent.domain.ReviewState
import com.example.engineeringagent.domain.StatusConsistency
import com.example.engineeringagent.domain.SummarySource
import com.example.engineeringagent.domain.TicketActivity
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeterministicSummarizerTest {

    private val summarizer = DeterministicSummarizer(
        Clock.fixed(Instant.parse("2026-08-25T09:00:00Z"), ZoneOffset.UTC),
    )

    private fun context(
        matches: List<ActivityMatch>,
        review: ReviewState = ReviewState(0, 0, 0, 0, 0, false, emptyList()),
        gitHubUnavailable: String? = null,
    ) = EngineeringContext(
        ticketKey = "ENG-185",
        issue = JiraIssue(
            id = "1", key = "ENG-185", summary = "Upgrade to Spring Boot 4.1", description = null,
            status = "In Progress", statusCategory = null, assignee = null, priority = null,
            issueType = null, acceptanceCriteria = null, created = null, updated = null, url = null,
        ),
        activity = TicketActivity(
            "ENG-185", MatchConfidence.MATCHED, matches, listOf("ENG-185"), gitHubUnavailable,
        ),
        codeChanges = CodeChangeSummary(matches.size, 2, 14, 109, 90, emptyList(), 0, emptyList(), emptyList(), false),
        reviewState = review,
        gaps = emptyList(),
        assembledAt = Instant.parse("2026-08-25T09:00:00Z"),
    )

    private fun match(
        number: Int = 28,
        state: PullRequestState = PullRequestState.MERGED,
        draft: Boolean = false,
        reviews: List<PullRequestReview> = emptyList(),
    ) = ActivityMatch(
        pullRequest = GitHubPullRequest(
            number, "acme/billing-service", "ENG-185: Upgrade to Spring Boot 4.1.0", null,
            state, draft, "octocat", "feature/ENG-185", "master", null, null, null, null,
        ),
        confidence = MatchConfidence.MATCHED,
        signals = listOf(MatchSignal.TICKET_KEY_IN_BRANCH),
        matchedVia = "ENG-185",
        reviews = reviews,
    )

    @Test
    fun `is labelled as deterministic so it is never mistaken for reasoning`() {
        val summary = summarizer.summarize(context(listOf(match())))
        assertEquals(SummarySource.DETERMINISTIC, summary.generatedBy)
    }

    @Test
    fun `reports merged work as completed`() {
        val summary = summarizer.summarize(context(listOf(match(state = PullRequestState.MERGED))))

        assertTrue(summary.completed.single().startsWith("Merged acme/billing-service#28"))
        assertTrue(summary.inProgress.isEmpty())
    }

    @Test
    fun `does not repeat the ticket key in the summary text`() {
        // Every renderer already leads with the key; repeating it yields "ENG-185 — ENG-185: …".
        val summary = summarizer.summarize(context(listOf(match())))

        assertFalse(summary.summary.startsWith("ENG-185"), summary.summary)
        assertTrue(summary.summary.startsWith("Upgrade to Spring Boot 4.1"), summary.summary)
    }

    @Test
    fun `does not ask for review comments to be addressed on a merged pull request`() {
        // A merged PR keeps its review history; treating that as outstanding tells the developer
        // to redo work they already finished.
        val summary = summarizer.summarize(
            context(
                listOf(
                    match(
                        state = PullRequestState.MERGED,
                        reviews = listOf(PullRequestReview("safesta", "CHANGES_REQUESTED", null, null)),
                    ),
                ),
                review = ReviewState(0, 1, 0, 0, 1, false, listOf("safesta")),
            ),
        )

        assertTrue(
            summary.nextSteps.none { it.contains("Address review comments") },
            "merged work needs no review follow-up: ${summary.nextSteps}",
        )
    }

    @Test
    fun `reports an open pull request as in progress with its review state`() {
        val summary = summarizer.summarize(
            context(
                listOf(
                    match(
                        state = PullRequestState.OPEN,
                        reviews = listOf(PullRequestReview("alice", "CHANGES_REQUESTED", null, null)),
                    ),
                ),
                review = ReviewState(1, 0, 0, 0, 1, false, listOf("alice")),
            ),
        )

        assertTrue(summary.inProgress.single().contains("changes requested"), summary.inProgress.toString())
        assertTrue(summary.nextSteps.any { it.contains("Address review comments") })
    }

    @Test
    fun `never claims to know whether the Jira status is consistent`() {
        // Judging that needs interpretation, which is exactly what this path lacks.
        assertEquals(StatusConsistency.UNKNOWN, summarizer.summarize(context(listOf(match()))).statusConsistency)
    }

    @Test
    fun `says plainly when there is no code activity`() {
        val summary = summarizer.summarize(context(emptyList()))

        assertTrue(summary.summary.contains("No code activity was found"))
        assertTrue(summary.completed.isEmpty())
        assertTrue(summary.confidence <= 0.2)
    }

    /**
     * Saying "no code activity was found" when GitHub was never reached states a fact the run does
     * not have, and the follow-up it produces tells the developer to link work that may already be
     * linked.
     */
    @Test
    fun `does not claim there was no code activity when GitHub could not be reached`() {
        val summary = summarizer.summarize(
            context(emptyList(), gitHubUnavailable = "GitHub is not configured."),
        )

        assertFalse(summary.summary.contains("No code activity was found"))
        assertTrue(summary.summary.contains("could not be checked"))
        assertFalse(summary.nextSteps.any { it.contains("Link the work") })
    }

    @Test
    fun `a draft is reported as a draft rather than awaiting review`() {
        val summary = summarizer.summarize(
            context(listOf(match(state = PullRequestState.OPEN, draft = true))),
        )
        assertTrue(summary.inProgress.single().contains("draft"))
    }
}
