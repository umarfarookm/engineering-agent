package com.example.engineeringagent.integration.github

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TicketKeyMatcherTest {

    // --- extractKeys: strict, because it discovers keys we did not already know ---

    @Test
    fun `extracts ticket keys from free text`() {
        assertEquals(
            listOf("ENG-80", "PLT-3206"),
            TicketKeyMatcher.extractKeys("As discussed in ENG-80, see PR for ticket PLT-3206"),
        )
    }

    @Test
    fun `de-duplicates and preserves order`() {
        assertEquals(
            listOf("PLT-3206", "ENG-80"),
            TicketKeyMatcher.extractKeys("PLT-3206 relates to ENG-80 and PLT-3206 again"),
        )
    }

    @Test
    fun `does not treat version numbers as ticket keys`() {
        assertTrue(TicketKeyMatcher.extractKeys("Upgrade to Spring Boot 4.1.0 and Kotlin 2.4").isEmpty())
        assertTrue(TicketKeyMatcher.extractKeys("released 2026-08-25").isEmpty())
    }

    @Test
    fun `requires an uppercase project prefix when discovering keys`() {
        // Lowercase is tolerated only when confirming a key we already know — see containsKey.
        assertTrue(TicketKeyMatcher.extractKeys("see plt 3206 for details").isEmpty())
    }

    // --- containsKey: lenient about shape, strict about identity ---

    @Test
    fun `confirms a key in a conventional branch name`() {
        assertTrue(TicketKeyMatcher.containsKey("feature/PLT-3256", "PLT-3256"))
        assertTrue(TicketKeyMatcher.containsKey("bugfix/ENG-282", "ENG-282"))
    }

    @Test
    fun `confirms a key in a title GitHub generated from a branch name`() {
        // GitHub rewrites feature/PLT-3206-monthly-email-reports into this, losing case and hyphen.
        assertTrue(
            TicketKeyMatcher.containsKey("Feature/plt 3206 monthly email reports", "PLT-3206"),
        )
    }

    @Test
    fun `does not match a longer ticket number`() {
        assertFalse(TicketKeyMatcher.containsKey("bugfix/ENG-2670", "ENG-267"))
        assertFalse(TicketKeyMatcher.containsKey("ENG-2670: unrelated work", "ENG-267"))
    }

    @Test
    fun `does not match a different project with the same number`() {
        assertFalse(TicketKeyMatcher.containsKey("PLT-267: other project", "ENG-267"))
    }

    @Test
    fun `does not match a key embedded in a longer identifier`() {
        assertFalse(TicketKeyMatcher.containsKey("XENG-267", "ENG-267"))
        assertFalse(TicketKeyMatcher.containsKey("release-2ENG-267", "ENG-267"))
    }

    @Test
    fun `handles absent text`() {
        assertFalse(TicketKeyMatcher.containsKey(null, "ENG-267"))
        assertFalse(TicketKeyMatcher.containsKey("", "ENG-267"))
    }

    // --- pull request URLs ---

    @Test
    fun `extracts a pull request url including a review comment anchor`() {
        val refs = TicketKeyMatcher.extractPullRequestUrls(
            "As discussed in PR for ticket PLT-3206: " +
                "https://github.com/acme/payments-service/pull/50#discussion_r2180211526",
        )
        assertEquals(1, refs.size)
        assertEquals("acme", refs.single().owner)
        assertEquals("payments-service", refs.single().repo)
        assertEquals(50, refs.single().number)
    }

    @Test
    fun `ignores non-pull-request github urls`() {
        assertTrue(
            TicketKeyMatcher.extractPullRequestUrls(
                "see https://github.com/acme/repo/issues/12 and https://github.com/acme/repo",
            ).isEmpty(),
        )
    }
}
