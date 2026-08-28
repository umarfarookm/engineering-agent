package com.example.engineeringagent.agent.context

import com.example.engineeringagent.config.ContextProperties
import com.example.engineeringagent.config.GitHubProperties
import com.example.engineeringagent.domain.ActivityMatch
import com.example.engineeringagent.domain.GapKind
import com.example.engineeringagent.domain.GitHubCommit
import com.example.engineeringagent.domain.GitHubFileChange
import com.example.engineeringagent.domain.GitHubPullRequest
import com.example.engineeringagent.domain.JiraIssue
import com.example.engineeringagent.domain.MatchConfidence
import com.example.engineeringagent.domain.MatchSignal
import com.example.engineeringagent.domain.PullRequestReview
import com.example.engineeringagent.domain.PullRequestState
import com.example.engineeringagent.domain.TicketActivity
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineeringContextAssemblerTest {

    private val clock = Clock.fixed(Instant.parse("2026-08-25T09:00:00Z"), ZoneOffset.UTC)

    private fun assembler(includeDiffs: Boolean = false, maxFiles: Int = 40) =
        EngineeringContextAssembler(
            GitHubProperties(token = "t", org = "acme", includeDiffs = includeDiffs),
            ContextProperties(maxFilesPerTicket = maxFiles),
            clock,
        )

    private fun issue(
        key: String = "ENG-185",
        description: String? = "Upgrade the service.",
        acceptanceCriteria: String? = null,
    ) = JiraIssue(
        id = "1", key = key, summary = "Upgrade to Spring Boot 4.1", description = description,
        status = "In Progress", statusCategory = "In Progress", assignee = null, priority = null,
        issueType = "Sub-task", acceptanceCriteria = acceptanceCriteria,
        created = null, updated = null, url = null,
    )

    private fun pr(
        number: Int = 28,
        state: PullRequestState = PullRequestState.OPEN,
        draft: Boolean = false,
    ) = GitHubPullRequest(
        number = number, repository = "acme/billing-service", title = "ENG-185: Upgrade",
        body = null, state = state, draft = draft, author = "octocat",
        branch = "feature/ENG-185", baseBranch = "master",
        createdAt = null, updatedAt = null, mergedAt = null, url = null,
    )

    private fun match(
        confidence: MatchConfidence = MatchConfidence.MATCHED,
        pullRequest: GitHubPullRequest = pr(),
        files: List<GitHubFileChange> = emptyList(),
        commits: List<GitHubCommit> = emptyList(),
        reviews: List<PullRequestReview> = emptyList(),
    ) = ActivityMatch(
        pullRequest = pullRequest, confidence = confidence,
        signals = listOf(MatchSignal.TICKET_KEY_IN_BRANCH), matchedVia = "ENG-185",
        commits = commits, changedFiles = files, reviews = reviews,
    )

    private fun activity(
        matches: List<ActivityMatch>,
        confidence: MatchConfidence = MatchConfidence.MATCHED,
        unavailable: String? = null,
    ) = TicketActivity("ENG-185", confidence, matches, listOf("ENG-185"), unavailable)

    private fun file(path: String, add: Int = 5, del: Int = 2, patch: String? = null) =
        GitHubFileChange(path, "modified", add, del, patch)

    @Test
    fun `aggregates change counts across pull requests`() {
        val context = assembler().assemble(
            issue(),
            activity(
                listOf(
                    match(files = listOf(file("pom.xml", 11, 25), file("src/main/kotlin/api/Handler.kt", 3, 1))),
                    match(pullRequest = pr(29), files = listOf(file("build.gradle.kts", 4, 4))),
                ),
            ),
        )

        assertEquals(2, context.codeChanges.pullRequestCount)
        assertEquals(3, context.codeChanges.filesChanged)
        assertEquals(18, context.codeChanges.additions)
        assertEquals(30, context.codeChanges.deletions)
    }

    @Test
    fun `derives areas from the directory containing each file`() {
        val context = assembler().assemble(
            issue(),
            activity(
                listOf(
                    match(
                        files = listOf(
                            file("src/main/kotlin/com/reports/EmailService.kt"),
                            file("src/main/kotlin/com/reports/RetryPolicy.kt"),
                            file("src/main/kotlin/com/config/AppConfig.kt"),
                        ),
                    ),
                ),
            ),
        )

        // "src/main/kotlin/com" says nothing; "reports" and "config" do.
        assertEquals(listOf("reports", "config"), context.codeChanges.areas)
    }

    @Test
    fun `does not report the organisation name as an area`() {
        // Reverse-domain package prefixes are identical across every file in the org, so reading
        // paths from the front returns "acme" for everything.
        val context = assembler().assemble(
            issue(),
            activity(
                listOf(
                    match(
                        files = listOf(
                            file("src/main/java/com/acme/ticketing/farecapping/config/AdminHeaderFilter.java"),
                            file("src/main/java/com/acme/ticketing/farecapping/config/WebConfig.java"),
                            file("pom.xml"),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf("config"), context.codeChanges.areas)
        assertFalse("acme" in context.codeChanges.areas)
        assertFalse("src" in context.codeChanges.areas)
    }

    @Test
    fun `only confirmed matches contribute evidence`() {
        val context = assembler().assemble(
            issue(),
            activity(
                listOf(
                    match(files = listOf(file("a.kt"))),
                    match(confidence = MatchConfidence.POSSIBLE_MATCH, pullRequest = pr(99), files = listOf(file("b.kt"))),
                ),
            ),
        )

        assertEquals(1, context.codeChanges.pullRequestCount, "a possible match is not evidence")
        assertEquals(1, context.codeChanges.filesChanged)
    }

    @Test
    fun `an unconfirmed match is reported as a gap rather than silently dropped`() {
        val context = assembler().assemble(
            issue(),
            activity(
                listOf(match(confidence = MatchConfidence.POSSIBLE_MATCH, pullRequest = pr(99))),
                confidence = MatchConfidence.POSSIBLE_MATCH,
            ),
        )

        val gap = context.gaps.single { it.kind == GapKind.UNCERTAIN_CODE_LINK }
        assertTrue(gap.detail.contains("billing-service#99"), gap.detail)
    }

    @Test
    fun `records that no GitHub activity was found`() {
        val context = assembler().assemble(issue(), activity(emptyList(), MatchConfidence.NO_MATCH))

        assertTrue(context.gaps.any { it.kind == GapKind.NO_GITHUB_ACTIVITY })
        assertFalse(
            context.gaps.any { it.kind == GapKind.GITHUB_UNAVAILABLE },
            "finding nothing is not the same as GitHub being down",
        )
    }

    @Test
    fun `distinguishes GitHub being unavailable from finding nothing`() {
        val context = assembler().assemble(
            issue(),
            activity(emptyList(), MatchConfidence.NO_MATCH, unavailable = "rate limit exhausted"),
        )

        assertTrue(context.gaps.any { it.kind == GapKind.GITHUB_UNAVAILABLE })
        assertFalse(context.gaps.any { it.kind == GapKind.NO_GITHUB_ACTIVITY })
    }

    @Test
    fun `records missing acceptance criteria so completion is not assumed`() {
        val context = assembler().assemble(issue(acceptanceCriteria = null), activity(listOf(match())))

        val gap = context.gaps.single { it.kind == GapKind.NO_ACCEPTANCE_CRITERIA }
        assertTrue(gap.detail.contains("cannot be checked"), gap.detail)
    }

    @Test
    fun `records an absent description`() {
        val context = assembler().assemble(issue(description = null), activity(listOf(match())))
        assertTrue(context.gaps.any { it.kind == GapKind.NO_DESCRIPTION })
    }

    @Test
    fun `excludes diffs by default and says so`() {
        val context = assembler().assemble(
            issue(),
            activity(listOf(match(files = listOf(file("a.kt", patch = "@@ secret @@"))))),
        )

        assertFalse(context.codeChanges.diffsIncluded)
        assertTrue(context.gaps.any { it.kind == GapKind.DIFFS_EXCLUDED })
    }

    @Test
    fun `truncates an oversized patch when diffs are enabled`() {
        val big = "x".repeat(5_000)
        val context = EngineeringContextAssembler(
            GitHubProperties(token = "t", org = "acme", includeDiffs = true),
            ContextProperties(maxPatchCharsPerFile = 100),
            clock,
        ).assemble(issue(), activity(listOf(match(files = listOf(file("a.kt", patch = big))))))

        val patch = context.codeChanges.files.single().patch!!
        assertTrue(patch.length < 200, "patch should be bounded, was ${patch.length}")
        assertTrue(patch.endsWith("… truncated"))
    }

    @Test
    fun `caps the file list and reports how many were omitted`() {
        val files = (1..50).map { file("src/main/kotlin/pkg/File$it.kt") }
        val context = assembler(maxFiles = 10).assemble(issue(), activity(listOf(match(files = files))))

        assertEquals(10, context.codeChanges.files.size)
        assertEquals(40, context.codeChanges.filesOmitted)
        assertEquals(50, context.codeChanges.filesChanged, "the true total must still be reported")
        assertTrue(context.gaps.any { it.kind == GapKind.FILES_TRUNCATED })
    }

    @Test
    fun `summarizes review state`() {
        val context = assembler().assemble(
            issue(),
            activity(
                listOf(
                    match(
                        pullRequest = pr(28, PullRequestState.OPEN),
                        reviews = listOf(
                            PullRequestReview("alice", "APPROVED", null, null),
                            PullRequestReview("bob", "CHANGES_REQUESTED", null, "please fix"),
                        ),
                    ),
                    match(pullRequest = pr(27, PullRequestState.MERGED)),
                ),
            ),
        )

        val review = context.reviewState
        assertEquals(1, review.openPullRequests)
        assertEquals(1, review.mergedPullRequests)
        assertEquals(1, review.approvals)
        assertEquals(1, review.changesRequested)
        assertEquals(listOf("alice", "bob"), review.reviewers)
    }

    @Test
    fun `an open pull request with no reviews is awaiting review`() {
        val context = assembler().assemble(issue(), activity(listOf(match(pullRequest = pr(28, PullRequestState.OPEN)))))
        assertTrue(context.reviewState.awaitingReview)
    }

    @Test
    fun `a draft pull request is not awaiting review`() {
        val context = assembler().assemble(
            issue(),
            activity(listOf(match(pullRequest = pr(28, PullRequestState.OPEN, draft = true)))),
        )

        assertFalse(context.reviewState.awaitingReview, "a draft is not waiting on anyone")
        assertEquals(1, context.reviewState.draftPullRequests)
    }

    @Test
    fun `collects distinct first lines of commit messages`() {
        val context = assembler().assemble(
            issue(),
            activity(
                listOf(
                    match(
                        commits = listOf(
                            GitHubCommit("a", "ENG-185: upgrade\n\nlong body that should not appear", null, null, null),
                            GitHubCommit("b", "ENG-185: upgrade", null, null, null),
                            GitHubCommit("c", "ENG-185: PR feedback", null, null, null),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf("ENG-185: upgrade", "ENG-185: PR feedback"), context.codeChanges.commitMessages)
        assertEquals(3, context.codeChanges.commitCount, "the true commit count is unaffected by de-duplication")
    }

    @Test
    fun `a complete context reports no spurious gaps`() {
        val context = assembler(includeDiffs = true).assemble(
            issue(acceptanceCriteria = "Given X, when Y, then Z"),
            activity(listOf(match(files = listOf(file("a.kt")), reviews = listOf(PullRequestReview("alice", "APPROVED", null, null))))),
        )

        assertTrue(context.gaps.isEmpty(), "unexpected gaps: ${context.gaps}")
        assertNull(context.activity.unavailableReason)
    }
}
