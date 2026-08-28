package com.example.engineeringagent.integration.github

/**
 * Finds Jira ticket references in free text.
 *
 * Two operations that look similar but must behave differently:
 *
 *  - [extractKeys] **discovers** keys we did not know about, so it is strict. Anything looser
 *    would harvest version numbers and random tokens as ticket keys.
 *  - [containsKey] **confirms** a key we are already looking for, so it can afford to be lenient
 *    about case and separator. GitHub rewrites `feature/PLT-3206-monthly-reports` into the PR title
 *    "Feature/plt 3206 monthly email reports" — same reference, different shape. Strict matching
 *    misses it; lenient matching is safe here only because the prefix and number are already known.
 */
object TicketKeyMatcher {

    private val KEY_PATTERN = Regex("""\b([A-Z][A-Z0-9]{1,9})-(\d+)\b""")

    private val PR_URL_PATTERN = Regex(
        """https?://(?:www\.)?github\.com/([\w.-]+)/([\w.-]+)/pull/(\d+)""",
        RegexOption.IGNORE_CASE,
    )

    /** Ticket keys explicitly present in [text], uppercased and de-duplicated in order. */
    fun extractKeys(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return KEY_PATTERN.findAll(text)
            .map { "${it.groupValues[1].uppercase()}-${it.groupValues[2]}" }
            .distinct()
            .toList()
    }

    /**
     * Whether [text] refers to [key], tolerating case and `-`, `_` or space as the separator.
     *
     * The trailing boundary rejects a longer number, so ENG-267 does not match ENG-2670.
     */
    fun containsKey(text: String?, key: String): Boolean {
        if (text.isNullOrBlank()) return false
        val (prefix, number) = key.split("-", limit = 2).takeIf { it.size == 2 } ?: return false
        val pattern = Regex(
            """(?<![A-Za-z0-9])${Regex.escape(prefix)}[-_ ]?${Regex.escape(number)}(?![0-9])""",
            RegexOption.IGNORE_CASE,
        )
        return pattern.containsMatchIn(text)
    }

    /** Pull request references stated outright in [text], as (owner, repo, number). */
    fun extractPullRequestUrls(text: String?): List<PullRequestReference> {
        if (text.isNullOrBlank()) return emptyList()
        return PR_URL_PATTERN.findAll(text)
            .map {
                PullRequestReference(
                    owner = it.groupValues[1],
                    repo = it.groupValues[2],
                    number = it.groupValues[3].toInt(),
                )
            }
            .distinct()
            .toList()
    }
}

data class PullRequestReference(val owner: String, val repo: String, val number: Int) {
    val fullName: String get() = "$owner/$repo"
}
