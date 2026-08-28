package com.example.engineeringagent.integration.github

import com.example.engineeringagent.config.GitHubProperties
import com.example.engineeringagent.domain.ActivityMatch
import com.example.engineeringagent.domain.GitHubPullRequest
import com.example.engineeringagent.domain.JiraIssue
import com.example.engineeringagent.domain.MatchConfidence
import com.example.engineeringagent.domain.MatchSignal
import com.example.engineeringagent.domain.TicketActivity
import com.example.engineeringagent.exception.EngineeringAgentException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Links a Jira issue to the GitHub work that belongs to it.
 *
 * The governing rule (ADR 0003) is that a wrong link is worse than no link: a confidently wrong
 * stand-up report destroys trust in the whole system, while "no activity found" is merely
 * unhelpful. So [MatchConfidence.MATCHED] requires an explicit ticket reference in a specific
 * field, and everything circumstantial stays [MatchConfidence.POSSIBLE_MATCH].
 */
@Component
class GitHubActivityMatcher(
    private val gitHubClient: GitHubClient,
    private val properties: GitHubProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun findActivity(issue: JiraIssue): TicketActivity {
        val candidates = candidateKeys(issue)
        val searchKeys = candidates.map { it.key }

        return try {
            val matches = (explicitlyLinked(issue) + searched(candidates))
                .groupBy { it.pullRequest.repository to it.pullRequest.number }
                .map { (_, duplicates) -> merge(duplicates) }
                .sortedWith(compareByDescending<ActivityMatch> { it.confidence == MatchConfidence.MATCHED }
                    .thenByDescending { it.pullRequest.updatedAt })

            val confidence = when {
                matches.any { it.confidence == MatchConfidence.MATCHED } -> MatchConfidence.MATCHED
                matches.isNotEmpty() -> MatchConfidence.POSSIBLE_MATCH
                else -> MatchConfidence.NO_MATCH
            }

            log.info(
                "Ticket {} matched {} pull request(s) at {} (keys searched: {})",
                issue.key, matches.size, confidence, searchKeys,
            )

            TicketActivity(
                ticketKey = issue.key,
                confidence = confidence,
                matches = matches,
                searchedKeys = searchKeys,
            )
        } catch (e: EngineeringAgentException) {
            // GitHub being down must not sink the whole report; "unknown" is distinct from "none".
            log.warn("GitHub unavailable while matching {}: {}", issue.key, e.message)
            TicketActivity(
                ticketKey = issue.key,
                confidence = MatchConfidence.NO_MATCH,
                matches = emptyList(),
                searchedKeys = searchKeys,
                unavailableReason = e.message,
            )
        }
    }

    /**
     * The keys worth searching for this ticket, each carrying how strongly it represents *this
     * ticket's* work.
     *
     * Two different claims are easy to conflate. That a pull request belongs to key X is one thing;
     * that key X is this ticket's work is another. The issue's own key, and keys its text
     * references ("as discussed in PR for ticket PLT-3206"), satisfy both — the ticket itself points
     * at them. A linked issue does not: a clone or a related ticket is somebody's work, but not
     * necessarily this ticket's, so anything found through it stays a possible match however
     * unambiguous the branch name is.
     */
    private fun candidateKeys(issue: JiraIssue): List<CandidateKey> {
        val own = listOf(CandidateKey(issue.key, ownWork = true))
        val referenced = (
            TicketKeyMatcher.extractKeys(issue.description) +
                issue.comments.flatMap { TicketKeyMatcher.extractKeys(it.body) }
            ).map { CandidateKey(it, ownWork = true) }
        val linked = issue.linkedIssues.map { CandidateKey(it.key, ownWork = false) }

        return (own + referenced + linked).distinctBy { it.key }
    }

    /**
     * Strategy 0: the issue names a pull request outright. This is a stated fact rather than an
     * inference, so it needs no corroboration — and it is the only strategy that finds work whose
     * branch and title carry no recognisable key at all.
     */
    private fun explicitlyLinked(issue: JiraIssue): List<ActivityMatch> {
        val text = listOfNotNull(issue.description) + issue.comments.map { it.body }
        return text.flatMap { TicketKeyMatcher.extractPullRequestUrls(it) }
            .distinct()
            .filter { inScope(it.fullName) }
            .mapNotNull { ref ->
                runCatching { gitHubClient.getPullRequest(ref.owner, ref.repo, ref.number) }
                    .onFailure { log.warn("Issue {} references {} which could not be loaded: {}", issue.key, ref, it.message) }
                    .getOrNull()
                    ?.let { pr ->
                        ActivityMatch(
                            pullRequest = pr,
                            confidence = MatchConfidence.MATCHED,
                            signals = listOf(MatchSignal.EXPLICIT_PR_URL_IN_ISSUE),
                            matchedVia = issue.key,
                        )
                    }
            }
    }

    private fun searched(candidates: List<CandidateKey>): List<ActivityMatch> =
        candidates.flatMap { candidate ->
            gitHubClient.searchPullRequests(candidate.key)
                .filter { inScope(it.repository) }
                .map { pr -> classify(pr, candidate) }
        }

    /**
     * Decides confidence from where the key actually appears.
     *
     * A search hit alone is not evidence: GitHub's full-text search also matches comments and
     * loosely-related text, so a result whose key cannot be located in the branch, title, or body
     * is reported as a possible match rather than promoted.
     */
    private fun classify(pr: GitHubPullRequest, candidate: CandidateKey): ActivityMatch {
        val key = candidate.key
        val signals = buildList {
            if (TicketKeyMatcher.containsKey(pr.branch, key)) add(MatchSignal.TICKET_KEY_IN_BRANCH)
            if (TicketKeyMatcher.containsKey(pr.title, key)) add(MatchSignal.TICKET_KEY_IN_PR_TITLE)
            if (TicketKeyMatcher.containsKey(pr.body, key)) add(MatchSignal.TICKET_KEY_IN_PR_BODY)
        }

        return when {
            signals.isEmpty() ->
                ActivityMatch(pr, MatchConfidence.POSSIBLE_MATCH, listOf(MatchSignal.UNCONFIRMED_SEARCH_HIT), key)
            !candidate.ownWork ->
                ActivityMatch(pr, MatchConfidence.POSSIBLE_MATCH, signals + MatchSignal.VIA_LINKED_ISSUE, key)
            else ->
                ActivityMatch(pr, MatchConfidence.MATCHED, signals, key)
        }
    }

    /** Collapses the same pull request found by several routes, keeping the strongest verdict. */
    private fun merge(duplicates: List<ActivityMatch>): ActivityMatch {
        val strongest = duplicates.minByOrNull { it.confidence.ordinal }!!
        return strongest.copy(
            signals = duplicates.flatMap { it.signals }.distinct().sortedBy { it.ordinal },
        )
    }

    /** A ticket key to search for, and whether it represents this ticket's own work. */
    private data class CandidateKey(val key: String, val ownWork: Boolean)

    private fun inScope(repositoryFullName: String): Boolean {
        if (repositoryFullName.isBlank()) return false
        if (properties.repos.isNotEmpty()) return repositoryFullName in properties.repos
        return repositoryFullName.substringBefore('/').equals(properties.org, ignoreCase = true)
    }
}
