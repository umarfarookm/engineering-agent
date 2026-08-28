package com.example.engineeringagent.integration.jira

import com.fasterxml.jackson.databind.JsonNode

/**
 * Flattens an Atlassian Document Format (ADF) node tree into plain text.
 *
 * Jira Cloud's REST v3 API returns descriptions and comments as ADF — a nested JSON document —
 * rather than the plain strings v2 returned. Everything downstream (matching, prompts, summaries)
 * wants readable text, so the conversion happens once, here, at the integration boundary.
 *
 * The goal is legible text, not a faithful renderer: formatting marks are dropped, block elements
 * become newlines, and list items are bulleted. Unknown node types are traversed rather than
 * rejected, so ADF additions degrade to their text content instead of throwing.
 */
object AdfTextExtractor {

    private val BLOCK_TYPES = setOf(
        "paragraph", "heading", "blockquote", "codeBlock", "rule",
        "panel", "listItem", "tableRow", "mediaSingle", "mediaGroup",
    )

    /** Returns flattened text, or null when the node is absent or carries no text at all. */
    fun extract(node: JsonNode?): String? {
        if (node == null || node.isNull) return null
        if (node.isTextual) return node.asText().ifBlank { null }

        val out = StringBuilder()
        append(node, out, listDepth = 0)
        return out.toString().trim().replace(Regex("\n{3,}"), "\n\n").ifBlank { null }
    }

    /**
     * Returns the text of the section introduced by the first heading matching [headingPattern],
     * up to the next heading of any level.
     *
     * This works on the ADF tree rather than on flattened text because flattening deliberately
     * discards heading markers — once a document is a plain string there is no reliable way to tell
     * a heading from an ordinary line, and the section runs on into whatever follows it.
     */
    fun extractSection(node: JsonNode?, headingPattern: Regex): String? {
        if (node == null || node.isNull) return null
        val content = node.path("content")
        if (!content.isArray) return null

        val blocks = content.toList()
        val headingIndex = blocks.indexOfFirst { block ->
            block.path("type").asText() == "heading" &&
                headingPattern.containsMatchIn(extract(block).orEmpty())
        }
        if (headingIndex < 0) return null

        val section = blocks.drop(headingIndex + 1)
            .takeWhile { it.path("type").asText() != "heading" }

        val out = StringBuilder()
        section.forEach { append(it, out, listDepth = 0) }
        return out.toString().trim().replace(Regex("\n{3,}"), "\n\n").ifBlank { null }
    }

    private fun append(node: JsonNode, out: StringBuilder, listDepth: Int) {
        when (node.path("type").asText()) {
            "text" -> out.append(node.path("text").asText())
            "hardBreak" -> out.append('\n')
            "mention" -> out.append(node.path("attrs").path("text").asText(""))
            "emoji" -> out.append(node.path("attrs").path("shortName").asText(""))
            "inlineCard", "blockCard" -> out.append(node.path("attrs").path("url").asText(""))
            "rule" -> out.append("\n---\n")
            else -> Unit
        }

        val type = node.path("type").asText()
        val nextDepth = if (type == "bulletList" || type == "orderedList") listDepth + 1 else listDepth

        if (type == "listItem") {
            out.append("  ".repeat((listDepth - 1).coerceAtLeast(0))).append("- ")
        }

        node.path("content").takeIf { it.isArray }?.forEach { child ->
            append(child, out, nextDepth)
        }

        if (type in BLOCK_TYPES) out.append('\n')
    }
}
