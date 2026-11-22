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
