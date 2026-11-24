package org.taigaui.designtokens.documentation.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.taigaui.designtokens.documentation.TaigaDocKind
import org.taigaui.designtokens.documentation.TaigaDocsIndex
import org.taigaui.designtokens.documentation.TaigaDocsSnapshot
import org.taigaui.designtokens.documentation.TaigaDocsSources
import org.taigaui.designtokens.documentation.TaigaEntityDoc
import org.taigaui.designtokens.documentation.TaigaUiProjectContext
import java.net.URI
import java.util.concurrent.CancellationException

class TaigaApiCatalogTest {
    @Test
    fun `ranks exact prefix substring and fuzzy matches across titles and identifiers`() {
        val ranked =
            listOf(
                entry("Slider", "bar"),
                entry("Bar"),
                entry("Bar Chart"),
                entry("Progress", "BarIndicator"),
                entry("Top Bar"),
                entry("Layout", "TuiSidebar"),
                entry("Badge Anchor"),
                entry("Popover", "TuiBackdropArea"),
            )
        val catalog = catalog(ranked.reversed() + entry("Checkbox", "TuiCheckbox"))

        assertEquals(ranked, catalog.search(" b-A_r ").map { it.entry.entity })
        assertTrue(catalog.search("#?").isEmpty())
        assertEquals(
            listOf(
                "Badge Anchor",
                "Bar",
                "Bar Chart",
                "Checkbox",
                "Layout",
                "Popover",
                "Progress",
                "Slider",
                "Top Bar",
            ),
            catalog.search(" ").map { it.entry.entity.title },
        )
    }

    @Test
    fun `keeps installed package metadata and truncates a long summary`() {
        val description = "A".repeat(139) + " longer description"
        val entity =
            entry(
                "Input",
            ).copy(packageNames = setOf("@taiga-ui/kit", "@taiga-ui/core"), description = description)
        val catalog = catalog(listOf(entity, entity.copy(description = "Duplicate must not replace the first section")))

        val match = catalog.search("Input").single().entry

        assertEquals("Component · @taiga-ui/core", match.metadata)
        assertEquals("A".repeat(139) + "…", match.summary)
        assertThrows(CancellationException::class.java) {
            catalog.search("Input") { throw CancellationException() }
        }
    }

    @Test
    fun `preserves short summaries and labels every documentation category`() {
        val entity = entry("Button").copy(description = "A short description")
        val match = catalog(listOf(entity)).search("Button").single().entry

        assertEquals("A short description", match.summary)
        assertEquals("Component", match.kindLabel)
        assertEquals(
            mapOf(
                TaigaDocKind.COMPONENT to "Components",
                TaigaDocKind.DIRECTIVE to "Directives",
                TaigaDocKind.PIPE to "Pipes",
                TaigaDocKind.SERVICE to "Services",
                TaigaDocKind.TYPE to "Types",
                TaigaDocKind.TOKEN to "Tokens",
                TaigaDocKind.UTILITY to "Utilities",
                TaigaDocKind.CLASS to "Classes",
                TaigaDocKind.UNKNOWN to "Other",
            ),
            TaigaDocKind.entries.associateWith { it.categoryLabel },
        )
    }

    @Test
    fun `short queries cannot use fuzzy matching and equal scores keep title order`() {
        val catalog = catalog(listOf(entry("Button Z"), entry("Button A"), entry("Badge Anchor")))

        assertEquals(listOf("Button A", "Button Z"), catalog.search("button").map { it.entry.entity.title })
        assertTrue(catalog.search("baq").isEmpty())
        assertTrue(catalog.search("bn").isEmpty())
    }

    private fun catalog(entities: List<TaigaEntityDoc>): TaigaApiCatalog {
        val context = TaigaUiProjectContext("5.18.0", 5, "@taiga-ui/core", setOf("@taiga-ui/core"), "test-scope")
        val index = TaigaDocsIndex(requireNotNull(TaigaDocsSources.forMajor(5)), entities)

        return TaigaApiCatalog(TaigaDocsSnapshot(context, index))
    }

    private fun entry(
        title: String,
        vararg symbols: String,
    ): TaigaEntityDoc =
        TaigaEntityDoc(
            sectionId = title.lowercase().replace(' ', '-'),
            title = title,
            packageNames = setOf("@taiga-ui/core"),
            kind = TaigaDocKind.COMPONENT,
            version = null,
            description = null,
            publicSymbols = emptySet(),
            selectors = symbols.toSet(),
            inputs = emptyList(),
            outputs = emptyList(),
            example = null,
            documentationUri = URI.create("https://taiga-ui.dev"),
        )
}
