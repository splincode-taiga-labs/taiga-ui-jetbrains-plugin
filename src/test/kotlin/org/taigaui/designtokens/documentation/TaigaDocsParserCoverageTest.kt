package org.taigaui.designtokens.documentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaigaDocsParserCoverageTest {
    private val parser = TaigaDocsParser()
    private val source = requireNotNull(TaigaDocsSources.forMajor(5))

    @Test
    fun `normalizes line endings metadata routes tables examples and selectors`() {
        val content =
            "\uFEFF" +
                listOf(
                    "# Import Map - Package Exports Reference",
                    "## @taiga-ui/core",
                    "**Utilitys:**",
                    "### HTTPServer",
                    FENCE,
                    "TuiHTTPServer",
                    FENCE,
                    "# utility/HTTPServer",
                    "- **Package**: CORE, @taiga-ui/kit / LEGACY",
                    "- **Type**: null",
                    "- **Version**: —",
                    "",
                    "First line",
                    "second line.",
                    "",
                    "### Example",
                    FENCE,
                    "<div tuiHTTPServer tui-http-server></div>",
                    FENCE,
                    "",
                    "### API - Inputs",
                    "| Property | Type | Description |",
                    "| :--- | ---: | --- |",
                    "| `[(value)]` | `A \\| B` | — |",
                    "| ignored | - | - |",
                ).joinToString("\r\n")

        val entity = requireNotNull(parser.parse(source, content)).entities.single()

        assertEquals("utils/http-server", entity.sectionId)
        assertEquals(TaigaDocKind.UTILITY, entity.kind)
        assertEquals(setOf("@taiga-ui/core", "@taiga-ui/kit", "@taiga-ui/legacy"), entity.packageNames)
        assertNull(entity.version)
        assertEquals("First line second line.", entity.description)
        assertEquals(setOf("TuiHTTPServer"), entity.publicSymbols)
        assertEquals(setOf("tui-http-server", "tuiHTTPServer"), entity.selectors)
        assertNull(requireNotNull(entity.example).language)
        assertEquals("value", entity.inputs[0].name)
        assertEquals("A | B", entity.inputs[0].documentedType)
        assertNull(entity.inputs[0].description)
        assertEquals("ignored", entity.inputs[1].name)
        assertNull(entity.inputs[1].documentedType)
        assertNull(entity.inputs[1].description)
    }

    @Test
    fun `canonicalizes singular routes and falls back to metadata or unknown kind`() {
        val content =
            listOf(
                "# directive/Foo",
                "- **Type**: component",
                "# pipe/Bar",
                "# service/Baz",
                "# type/Qux",
                "# token/Tokens",
                "# class/Thing",
                "# strange/Unknown",
                "- **Type**: directives",
            ).joinToString("\n")
        val entities = requireNotNull(parser.parse(source, content)).entities.associateBy(TaigaEntityDoc::title)

        assertEquals(TaigaDocKind.DIRECTIVE, entities.getValue("Foo").kind)
        assertEquals(TaigaDocKind.PIPE, entities.getValue("Bar").kind)
        assertEquals(TaigaDocKind.SERVICE, entities.getValue("Baz").kind)
        assertEquals(TaigaDocKind.TYPE, entities.getValue("Qux").kind)
        assertEquals(TaigaDocKind.TOKEN, entities.getValue("Tokens").kind)
        assertEquals(TaigaDocKind.CLASS, entities.getValue("Thing").kind)
        assertEquals(TaigaDocKind.DIRECTIVE, entities.getValue("Unknown").kind)
        assertTrue(entities.values.all { entity -> entity.packageNames.isEmpty() })
    }

    @Test
    fun `ignores incomplete import map malformed tables and interrupted examples`() {
        val content =
            listOf(
                "# Import Map - Package Exports Reference",
                "## @taiga-ui/core",
                "**Components:**",
                FENCE,
                "TuiIgnored",
                FENCE,
                "### empty",
                FENCE,
                "!!!",
                FENCE,
                "# components/Empty",
                "- **Package**: null",
                "- **Version**: 1.0.0",
                "### Example",
                "text before fence",
                "### API - Inputs",
                "| only | two |",
                "| Property | Type | Description |",
                "| --- | --- | --- |",
                "| `` | string | empty name |",
            ).joinToString("\n")

        val entity = requireNotNull(parser.parse(source, content)).entities.single()

        assertEquals(emptySet<String>(), entity.publicSymbols)
        assertEquals(emptySet<String>(), entity.packageNames)
        assertNull(entity.description)
        assertNull(entity.example)
        assertTrue(entity.inputs.isEmpty())
        assertEquals("1.0.0", entity.version)
    }

    @Test
    fun `empty example code is discarded and package aliases are sanitized`() {
        val content =
            listOf(
                "# component/TestValue",
                "- **Package**: SOME_PACKAGE / bad package / @taiga-ui/direct",
                "### Example",
                FENCE + "html",
                "   ",
                FENCE,
            ).joinToString("\n")

        val entity = requireNotNull(parser.parse(source, content)).entities.single()

        assertNull(entity.example)
        assertEquals(setOf("@taiga-ui/some-package", "@taiga-ui/direct"), entity.packageNames)
        assertEquals(TaigaDocKind.COMPONENT, entity.kind)
    }

    private companion object {
        const val FENCE = "```"
    }
}
