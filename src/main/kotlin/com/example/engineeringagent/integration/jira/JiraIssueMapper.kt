package com.example.engineeringagent.integration.jira

import com.example.engineeringagent.config.JiraProperties
import com.example.engineeringagent.domain.JiraComment
import com.example.engineeringagent.domain.JiraIssue
import com.example.engineeringagent.domain.JiraUser
import com.example.engineeringagent.domain.LinkedIssue
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Maps Jira REST payloads to normalized domain models.
 *
 * Every field is read defensively: Jira omits fields the account cannot see, returns nulls for
 * unset values, and varies shape between issue types. A missing field becomes null — never a
 * fabricated default.
 */
@Component
class JiraIssueMapper {

    fun toDomain(issue: JsonNode, properties: JiraProperties): JiraIssue {
        val fields = issue.path("fields")
        val description = AdfTextExtractor.extract(fields.path("description"))

        return JiraIssue(
            id = issue.path("id").asText(""),
            key = issue.path("key").asText(""),
            summary = fields.path("summary").asText(""),
            description = description,
            status = fields.path("status").path("name").asText("Unknown"),
            statusCategory = fields.path("status").path("statusCategory").path("name").textValue(),
            assignee = toUser(fields.path("assignee")),
            priority = fields.path("priority").path("name").textValue(),
            labels = fields.path("labels").mapNotNull { it.textValue() },
            issueType = fields.path("issuetype").path("name").textValue(),
            comments = fields.path("comment").path("comments").map(::toComment),
            acceptanceCriteria = extractAcceptanceCriteria(fields, properties),
            linkedIssues = fields.path("issuelinks").mapNotNull(::toLinkedIssue),
            created = parseInstant(fields.path("created").textValue()),
            updated = parseInstant(fields.path("updated").textValue()),
            url = issueUrl(properties.baseUrl, issue.path("key").textValue()),
        )
    }

    fun toComment(comment: JsonNode): JiraComment = JiraComment(
        id = comment.path("id").asText(""),
        author = comment.path("author").path("displayName").textValue(),
        body = AdfTextExtractor.extract(comment.path("body")).orEmpty(),
        created = parseInstant(comment.path("created").textValue()),
        updated = parseInstant(comment.path("updated").textValue()),
    )

    private fun toUser(node: JsonNode): JiraUser? {
        if (node.isMissingNode || node.isNull) return null
        return JiraUser(
            accountId = node.path("accountId").textValue(),
            displayName = node.path("displayName").textValue(),
            email = node.path("emailAddress").textValue(),
        )
    }

    /**
     * Issue links carry the other issue under either `outwardIssue` or `inwardIssue`, with the
     * matching human-readable relationship on the link type. Which side is populated tells us the
     * direction, so the relationship reads correctly from this issue's perspective.
     */
    private fun toLinkedIssue(link: JsonNode): LinkedIssue? {
        val outward = link.path("outwardIssue")
        val inward = link.path("inwardIssue")
        val (other, relationship) = when {
            !outward.isMissingNode && !outward.isNull ->
                outward to link.path("type").path("outward").asText("relates to")
            !inward.isMissingNode && !inward.isNull ->
                inward to link.path("type").path("inward").asText("relates to")
            else -> return null
        }

        return LinkedIssue(
            key = other.path("key").asText(""),
            summary = other.path("fields").path("summary").textValue(),
            status = other.path("fields").path("status").path("name").textValue(),
            relationship = relationship,
        )
    }

    /**
     * Acceptance criteria come from a configured custom field when the project has one; otherwise
     * they are read from an "Acceptance Criteria" heading in the description. When neither yields
     * anything the result is null, which downstream treats as "unknown" rather than "none".
     */
    private fun extractAcceptanceCriteria(fields: JsonNode, properties: JiraProperties): String? {
        properties.acceptanceCriteriaField
            ?.let { AdfTextExtractor.extract(fields.path(it)) }
            ?.let { return it }

        return AdfTextExtractor.extractSection(fields.path("description"), ACCEPTANCE_HEADING)
    }

    private fun issueUrl(baseUrl: String, key: String?): String? =
        if (baseUrl.isBlank() || key.isNullOrBlank()) null
        else "${baseUrl.trimEnd('/')}/browse/$key"

    private fun parseInstant(value: String?): Instant? =
        value?.let {
            runCatching { OffsetDateTime.parse(it, JIRA_TIMESTAMP).toInstant() }.getOrNull()
        }

    private companion object {
        /** Jira emits offsets without a colon, e.g. 2026-08-24T10:15:30.000+0000. */
        val JIRA_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        val ACCEPTANCE_HEADING = Regex("""^\s*acceptance\s+criteria\s*:?\s*$""", RegexOption.IGNORE_CASE)
    }
}
