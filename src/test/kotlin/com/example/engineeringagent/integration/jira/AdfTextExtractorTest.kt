package com.example.engineeringagent.integration.jira

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdfTextExtractorTest {

    private val json = ObjectMapper()

    private fun adf(content: String) = json.readTree("""{"type":"doc","version":1,"content":$content}""")

    @Test
    fun `flattens paragraphs to text`() {
        val node = adf("""[{"type":"paragraph","content":[{"type":"text","text":"Hello world"}]}]""")
        assertEquals("Hello world", AdfTextExtractor.extract(node))
    }

    @Test
    fun `bullet lists become dashed lines`() {
        val node = adf(
            """[{"type":"bulletList","content":[
                 {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"first"}]}]},
                 {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"second"}]}]}
               ]}]""",
        )
        val text = AdfTextExtractor.extract(node)!!
        assertTrue(text.contains("- first"), text)
        assertTrue(text.contains("- second"), text)
    }

    @Test
    fun `mentions and inline cards contribute their text`() {
        val node = adf(
            """[{"type":"paragraph","content":[
                 {"type":"text","text":"ping "},
                 {"type":"mention","attrs":{"text":"@Priya"}},
                 {"type":"text","text":" see "},
                 {"type":"inlineCard","attrs":{"url":"https://example.com/x"}}
               ]}]""",
        )
        assertEquals("ping @Priya see https://example.com/x", AdfTextExtractor.extract(node))
    }

    @Test
    fun `unknown node types degrade to their text content rather than throwing`() {
        val node = adf(
            """[{"type":"someFutureAdfNode","content":[{"type":"text","text":"still readable"}]}]""",
        )
        assertEquals("still readable", AdfTextExtractor.extract(node))
    }

    @Test
    fun `absent and empty documents yield null, never an empty string`() {
        assertNull(AdfTextExtractor.extract(null))
        assertNull(AdfTextExtractor.extract(json.nullNode()))
        assertNull(AdfTextExtractor.extract(adf("[]")))
    }
}
