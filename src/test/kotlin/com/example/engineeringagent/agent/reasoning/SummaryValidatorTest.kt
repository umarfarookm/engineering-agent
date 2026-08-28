package com.example.engineeringagent.agent.reasoning

import com.example.engineeringagent.domain.ActivityMatch
import com.example.engineeringagent.domain.CodeChangeSummary
import com.example.engineeringagent.domain.ContextGap
import com.example.engineeringagent.domain.EngineeringContext
import com.example.engineeringagent.domain.GapKind
import com.example.engineeringagent.domain.GitHubPullRequest
import com.example.engineeringagent.domain.JiraIssue
import com.example.engineeringagent.domain.MatchConfidence
import com.example.engineeringagent.domain.MatchSignal
import com.example.engineeringagent.domain.PullRequestState
import com.example.engineeringagent.domain.ReviewState
import com.example.engineeringagent.domain.StatusConsistency
import com.example.engineeringagent.domain.TicketActivity
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SummaryValidatorTest {

    private val json = ObjectMapper()
    private val validator = SummaryValidator()

    private fun context(
        key: String = "ENG-267",
        matches: List<ActivityMatch> = emptyList(),
        gaps: List<ContextGap> = emptyList(),
    ) = EngineeringContext(
        ticketKey = key,
        issue = JiraIssue(
            id = "1", key = key, summary = "s", description = null, status = "In Progress",
            statusCategory = null, assignee = null, priority = null, issueType = null,
            acceptanceCriteria = null, created = null, updated = null, url = null,
        ),
        activity = TicketActivity(
            key,
            if (matches.isEmpty()) MatchConfidence.NO_MATCH else MatchConfidence.MATCHED,
            matches, listOf(key),
        ),
        codeChanges = CodeChangeSummary(matches.size, 0, 0, 0, 0, emptyList(), 0, emptyList(), emptyList(), false),
        reviewState = ReviewState(0, 0, 0, 0, 0, false, emptyList()),
        gaps = gaps,
        assembledAt = Instant.parse("2026-08-25T09:00:00Z"),
    )

    private fun contextWithMergedPr() = context(key = "ENG-185", matches = listOf(confirmedMatch()))

    private fun confirmedMatch() = ActivityMatch(
        pullRequest = GitHubPullRequest(
            28, "acme/repo", "ENG-185: x", null, PullRequestState.MERGED, false,
            "octocat", "feature/ENG-185", "master", null, null, null, null,
        ),
        confidence = MatchConfidence.MATCHED,
        signals = listOf(MatchSignal.TICKET_KEY_IN_BRANCH),
        matchedVia = "ENG-185",
    )

    private fun response(vararg pairs: Pair<String, Any?>): com.fasterxml.jackson.databind.JsonNode {
        val node = json.createObjectNode()
        node.put("ticketKey", "ENG-267")
        node.put("summary", "Some work happened.")
        listOf("completed", "inProgress", "remaining", "blockers", "nextSteps").forEach {
            node.set<com.fasterxml.jackson.databind.JsonNode>(it, json.createArrayNode())
        }
        node.put("statusConsistency", "UNKNOWN")
        node.put("confidence", 0.5)
        pairs.forEach { (k, v) ->
            when (v) {
                is List<*> -> node.set<com.fasterxml.jackson.databind.JsonNode>(
                    k, json.createArrayNode().apply { v.forEach { add(it.toString()) } },
                )
                is Double -> node.put(k, v)
                is String -> node.put(k, v)
                null -> node.putNull(k)
            }
        }
        return node
    }

    @Test
    fun `drops claims of completed work when no code evidence exists`() {
        // The failure this system exists to prevent: a confident stand-up about work that has no
        // trace in GitHub.
        val summary = validator.validate(
            response("completed" to listOf("Fixed the refund bug", "Deployed to production")),
            context(gaps = listOf(ContextGap(GapKind.NO_GITHUB_ACTIVITY, "No pull request references ENG-267."))),
        )

        assertTrue(summary.completed.isEmpty(), "unsupported completion claims must not survive")
        assertTrue(
            summary.notes.any { it.contains("Removed 2 claim(s)") },
            "the correction must be visible, not silent: ${summary.notes}",
        )
    }

    /**
     * When GitHub cannot be reached there is no NO_GITHUB_ACTIVITY gap, only GITHUB_UNAVAILABLE.
     * Keying the drop off that gap let unevidenced completion claims through in precisely the case
     * where the evidence is weakest — nothing was checked at all.
     */
    @Test
    fun `drops claims of completed work when GitHub could not be reached`() {
        val ctx = context(gaps = listOf(ContextGap(GapKind.GITHUB_UNAVAILABLE, "GitHub is not configured.")))

        val summary = validator.validate(
            response("completed" to listOf("Shipped the retry policy")),
            ctx,
        )

        assertTrue(summary.completed.isEmpty(), "nothing was verified, so nothing can be claimed")
    }

    @Test
    fun `keeps completed work when a pull request confirms it`() {
        val summary = validator.validate(
            response("completed" to listOf("Merged the upgrade")),
            context(matches = listOf(confirmedMatch())),
        )

        assertEquals(listOf("Merged the upgrade"), summary.completed)
    }

    @Test
    fun `caps confidence when nothing confirms the ticket`() {
        val summary = validator.validate(response("confidence" to 0.95), context())

        assertTrue(summary.confidence <= 0.3, "was ${summary.confidence}")
        assertTrue(summary.notes.any { it.contains("Lowered confidence") })
    }

    @Test
    fun `clamps confidence to the valid range`() {
        val summary = validator.validate(response("confidence" to 7.0), context(matches = listOf(confirmedMatch())))
        assertTrue(summary.confidence <= 1.0)
    }

    @Test
    fun `overrides a wrong ticket key and says so`() {
        // Models echo keys they saw in the text; the caller knows which ticket this actually is.
        val summary = validator.validate(
            response("ticketKey" to "PLT-3206"),
            context(key = "ENG-80", matches = listOf(confirmedMatch())),
        )

        assertEquals("ENG-80", summary.ticketKey)
        assertTrue(summary.notes.any { it.contains("wrong ticket key") })
    }

    @Test
    fun `discards Unknown placeholders from lists`() {
        val summary = validator.validate(
            response("nextSteps" to listOf("Unknown", "Address review comments")),
            context(matches = listOf(confirmedMatch())),
        )

        assertEquals(listOf("Address review comments"), summary.nextSteps)
    }

    @Test
    fun `rejects an empty summary outright`() {
        assertFailsWith<InvalidSummaryException> {
            validator.validate(response("summary" to ""), context())
        }
    }

    @Test
    fun `carries evidence gaps into notes regardless of what the model said`() {
        val summary = validator.validate(
            response(),
            context(
                matches = listOf(confirmedMatch()),
                gaps = listOf(ContextGap(GapKind.UNCERTAIN_CODE_LINK, "2 pull request(s) could not be confirmed")),
            ),
        )

        assertTrue(summary.notes.any { it.contains("could not be confirmed") })
    }

    @Test
    fun `falls back to UNKNOWN for an unrecognised status consistency`() {
        val summary = validator.validate(
            response("statusConsistency" to "PROBABLY_FINE"),
            context(matches = listOf(confirmedMatch())),
        )

        assertEquals(StatusConsistency.UNKNOWN, summary.statusConsistency)
    }

    @Test
    fun `de-duplicates repeated list entries`() {
        val summary = validator.validate(
            response("inProgress" to listOf("PR awaiting review", "PR awaiting review")),
            context(matches = listOf(confirmedMatch())),
        )

        assertEquals(1, summary.inProgress.size)
    }

    @Test
    fun `discards bare state words that are not statements`() {
        // A 3B model asked what was completed answers "merged" — echoing the evidence's vocabulary
        // rather than describing the work. That is not something anyone can say in a stand-up.
        val summary = validator.validate(
            response(
                "completed" to listOf("merged", "Merged the Spring Boot 4.1 upgrade"),
                "inProgress" to listOf("open", "done"),
            ),
            context(matches = listOf(confirmedMatch())),
        )

        assertEquals(listOf("Merged the Spring Boot 4.1 upgrade"), summary.completed)
        assertTrue(summary.inProgress.isEmpty(), "bare states must not survive: ${summary.inProgress}")
    }

    @Test
    fun `flags in-progress claims when every pull request is already merged`() {
        // Observed with llama3.2:3b: it described a merged PR as "open with changes requested".
        // The claim survives — work can be in progress without an open PR — but the contradiction
        // with the code evidence is recorded so a reader is not misled.
        val summary = validator.validate(
            response("inProgress" to listOf("The Spring Boot upgrade is open with changes requested")),
            contextWithMergedPr(),
        )

        assertEquals(1, summary.inProgress.size, "the claim itself is not deleted")
        assertTrue(
            summary.notes.any { it.contains("not evidenced by the code") },
            "the contradiction must be visible: ${summary.notes}",
        )
    }

    @Test
    fun `does not flag in-progress work when a pull request is genuinely open`() {
        val open = ActivityMatch(
            pullRequest = GitHubPullRequest(
                29, "acme/repo", "ENG-185: x", null, PullRequestState.OPEN, false,
                "octocat", "feature/ENG-185", "master", null, null, null, null,
            ),
            confidence = MatchConfidence.MATCHED,
            signals = listOf(MatchSignal.TICKET_KEY_IN_BRANCH),
            matchedVia = "ENG-185",
        )
        val ctx = context(key = "ENG-185", matches = listOf(open))
            .copy(reviewState = ReviewState(1, 0, 0, 0, 0, true, emptyList()))

        val summary = validator.validate(response("inProgress" to listOf("Upgrade is open for review")), ctx)

        assertTrue(summary.notes.none { it.contains("not evidenced by the code") })
    }
}
