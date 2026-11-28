package org.taigaui.designtokens.documentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.taigaui.designtokens.documentation.discovery.TaigaApiCatalog
import org.taigaui.designtokens.documentation.discovery.categoryLabel

class TaigaDocsParserTest {
    private val parser = TaigaDocsParser()

    @Test
    fun parsesCurrentV5ImportMapComponentDocsInputsOutputsAndSelectors() {
        val index = requireNotNull(parser.parse(requireNotNull(TaigaDocsSources.forMajor(5)), v5Docs()))
        val button = index.findByPublicSymbol("TuiButton").single()
        val calendar = index.findBySelector("tui-calendar").single()

        assertEquals("components/button", button.sectionId)
        assertEquals(setOf("@taiga-ui/core"), button.packageNames)
        assertEquals(TaigaDocKind.COMPONENT, button.kind)
        assertEquals(setOf("TuiButton", "TuiButtonOptions"), button.publicSymbols)
        assertEquals(setOf("tuiButton"), button.selectors)
        assertEquals("size", button.inputs.single().name)
        assertEquals("TuiSizeXS | TuiSizeL", button.inputs.single().documentedType)
        assertTrue(requireNotNull(button.description).startsWith("Button is a basic component"))
        assertEquals("html", requireNotNull(button.example).language)
        assertEquals("dayClick", calendar.outputs.single().name)
        assertEquals("TuiDay", calendar.outputs.single().documentedType)
        assertEquals("https://taiga-ui.dev/components/calendar", calendar.documentationUri.toString())
        assertNotNull(index.findBySectionId("components/CALENDAR"))
    }

    @Test
    fun parsesRepresentativeV4DocsWithVersionedLinks() {
        val index = requireNotNull(parser.parse(requireNotNull(TaigaDocsSources.forMajor(4)), v4Docs()))
        val input = index.findByPublicSymbol("TuiInputModule").single()

        assertEquals("4.60.0", input.version)
        assertEquals(setOf("@taiga-ui/legacy"), input.packageNames)
        assertEquals(setOf("tui-input"), input.selectors)
        assertEquals("https://taiga-ui.dev/v4/components/input", input.documentationUri.toString())
    }

    @Test
    fun keepsGuideHeadingsInsideCodeBlocks() {
        val docs =
            listOf(
                "# Code Generation Checklist",
                "Check installed imports.",
                "# components/Button",
                "Button documentation.",
                "# Migration Guide",
                "### Angular CLI",
                "```sh",
                "# Install dependencies first",
                "ng update",
                "```",
            ).joinToString("\n")
        val index = requireNotNull(parser.parse(requireNotNull(TaigaDocsSources.forMajor(5)), docs))

        assertEquals("Check installed imports.", index.guides[TaigaDocsGuide.GENERATION_CHECKLIST])
        val guide = requireNotNull(index.guides[TaigaDocsGuide.MIGRATION])
        assertTrue(guide.contains("# Install dependencies first\nng update"))
        assertEquals(listOf("components/button"), index.entities.map { it.sectionId })
    }

    @Test
    fun discoveryRanksExactIdentifiersAndFindsFuzzyTitlesFromTheSameCatalog() {
        val catalog =
            discoveryCatalog(
                v5Docs() + "\n# components/TuiButton\n# components/Button Group\n# components/Icon Button",
            )

        val exact = catalog.search("TuiButton").first()
        val selector = catalog.search("<tui-calendar>").single()
        val fuzzy = catalog.search("clndr").single()

        assertEquals("components/button", exact.entry.entity.sectionId)
        assertEquals(
            listOf("Button", "Button Group", "Icon Button", "TuiButton"),
            catalog.search("button").map { it.entry.entity.title },
        )
        assertEquals("Calendar", selector.entry.entity.title)
        assertEquals("Calendar", fuzzy.entry.entity.title)
        assertTrue(catalog.search("xyz").isEmpty())
        assertTrue(catalog.search("/").isEmpty())
    }

    @Test
    fun discoveryFiltersByInstalledPackagesAndVersionBeforeListingTheTree() {
        val docs =
            v5Docs() +
                "\n# pipes/Future Pipe\n- **Package**: `CORE`\n- **Version**: 5.2.0" +
                "\n# components/Grid\n- **Package**: `KIT`" +
                "\n# pipes/Present Pipe\n- **Package**: `CORE, KIT`"
        val catalog = discoveryCatalog(docs)
        val entries = catalog.search("").map { it.entry }
        val pipe = catalog.search("Present Pipe").single().entry

        assertEquals(listOf("Button", "Calendar", "Present Pipe"), entries.map { it.entity.title })
        assertEquals("Pipes", pipe.entity.kind.categoryLabel)
        assertEquals(listOf("@taiga-ui/core"), pipe.packages)
        assertTrue(catalog.search("Future Pipe").isEmpty())
        assertTrue(catalog.search("Grid").isEmpty())
    }

    private fun discoveryCatalog(content: String): TaigaApiCatalog {
        val index = requireNotNull(parser.parse(requireNotNull(TaigaDocsSources.forMajor(5)), content))
        val context =
            TaigaUiProjectContext(
                version = "5.1.0",
                majorVersion = 5,
                versionSourcePackage = "@taiga-ui/core",
                installedPackages = setOf("@taiga-ui/core"),
                packageScopeIdentity = "test",
            )

        return TaigaApiCatalog(TaigaDocsSnapshot(context, index))
    }

    @Test
    fun rejectsEmptyAndMalformedDocuments() {
        val source = requireNotNull(TaigaDocsSources.forMajor(5))

        assertEquals(null, parser.parse(source, ""))
        assertEquals(null, parser.parse(source, "not Taiga UI documentation"))
    }

    @Test
    fun parsesUnescapedAndEscapedUnionTypesWithoutShiftingDescriptions() {
        val rows =
            listOf(
                "| [size] | `TuiSizeXS | TuiSizeL` | Size of the button |",
                "| [escaped] | `A \\| B` | An escaped union |",
                "| [doubled] | `A \\\\| B` | A generated union |",
                "| [callback] | ``(value: `x` | `y`) => void`` | A callback |",
                "| [loading] | `boolean` | ) |",
            )
        val docs =
            "# components/Button\n### API - Inputs\n| Property | Type | Description |\n| --- | --- | --- |\n" +
                rows.joinToString("\n")
        val inputs =
            requireNotNull(
                parser.parse(requireNotNull(TaigaDocsSources.forMajor(4)), docs),
            ).entities.single().inputs
        assertEquals("TuiSizeXS | TuiSizeL", inputs[0].documentedType)
        assertEquals("Size of the button", inputs[0].description)
        assertEquals("A | B", inputs[1].documentedType)
        assertEquals("An escaped union", inputs[1].description)
        assertEquals("A | B", inputs[2].documentedType)
        assertEquals("A generated union", inputs[2].description)
        assertEquals("(value: `x` | `y`) => void", inputs[3].documentedType)
        assertEquals("A callback", inputs[3].description)
        assertEquals(null, inputs[4].description)
    }

    private fun v5Docs(): String =
        listOf(
            "# Import Map - Package Exports Reference",
            "",
            "## @taiga-ui/core",
            "",
            "**Components:**",
            "",
            "### button",
            FENCE + "text",
            "TuiButton, TuiButtonOptions",
            FENCE,
            "",
            "### calendar",
            FENCE + "text",
            "TuiCalendar",
            FENCE,
            "",
            "# components/Button",
            "- **Package**: " + tick("CORE"),
            "- **Type**: components",
            "- **Version**: 5.0.0",
            "",
            "Button is a basic component used to trigger actions.",
            "",
            "### Example",
            FENCE + "html",
            "<button tuiButton size=\"m\">Button</button>",
            FENCE,
            "",
            "### API - Inputs",
            "| Property | Type | Description |",
            "| --- | --- | --- |",
            "| " + tick("[size]") + " | " + tick("TuiSizeXS \\| TuiSizeL") + " | Button size |",
            "",
            "# components/Calendar",
            "- **Package**: " + tick("CORE"),
            "- **Type**: components",
            "- **Version**: 5.0.0",
            "",
            "Calendar displays a month.",
            "",
            "### Example",
            FENCE + "html",
            "<tui-calendar (dayClick)=\"onDayClick(\$event)\" />",
            FENCE,
            "",
            "### API - Outputs",
            "| Event | Type | Description |",
            "| --- | --- | --- |",
            "| " + tick("(dayClick)") + " | " + tick("TuiDay") + " | Date click |",
        ).joinToString("\n")

    private fun v4Docs(): String =
        listOf(
            "# Import Map - Package Exports Reference",
            "",
            "## @taiga-ui/legacy",
            "",
            "**Components:**",
            "",
            "### input",
            FENCE + "text",
            "TuiInputModule",
            FENCE,
            "",
            "# components/Input",
            "- **Package**: " + tick("LEGACY"),
            "- **Type**: components",
            "- **Version**: 4.60.0",
            "",
            "Legacy input component.",
            "",
            "### Example",
            FENCE + "html",
            "<tui-input>Value</tui-input>",
            FENCE,
        ).joinToString("\n")

    private fun tick(value: String): String = BACKTICK + value + BACKTICK

    private companion object {
        const val BACKTICK = "\u0060"
        const val FENCE = "\u0060\u0060\u0060"
    }
}
