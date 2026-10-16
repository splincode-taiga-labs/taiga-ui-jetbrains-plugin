package org.taigaui.designtokens.documentation

import org.junit.Assert.assertEquals
import org.junit.Test

class TaigaDocsEntityKindsTest {
    @Test
    fun parsesDirectivePipeAndTokenEntities() {
        val source = requireNotNull(TaigaDocsSources.forMajor(5))
        val index = requireNotNull(TaigaDocsParser().parse(source, docs()))

        assertEquals(
            TaigaDocKind.DIRECTIVE,
            index.findByPublicSymbol("TuiAppearance").single().kind,
        )
        assertEquals(
            TaigaDocKind.PIPE,
            index.findByPublicSymbol("TuiAmountPipe").single().kind,
        )
        assertEquals(
            TaigaDocKind.TOKEN,
            requireNotNull(index.findBySectionId("utils/tokens")).kind,
        )
        assertEquals("TuiAmountPipe", index.findBySelector("tuiAmount").single().publicSymbols.single())
    }

    private fun docs(): String =
        listOf(
            "# Import Map - Package Exports Reference",
            "",
            "## @taiga-ui/core",
            "**Directives:**",
            "### appearance",
            FENCE + "text",
            "TuiAppearance",
            FENCE,
            "**Pipes:**",
            "### amount",
            FENCE + "text",
            "TuiAmountPipe",
            FENCE,
            "**Tokens:**",
            "### tokens",
            FENCE + "text",
            "TUI_COMMON_ICONS",
            FENCE,
            "",
            "# directives/Appearance",
            "- **Package**: " + tick("CORE"),
            "- **Type**: directives",
            "- **Version**: 5.0.0",
            "",
            "Appearance directive.",
            "",
            "# pipes/Amount",
            "- **Package**: " + tick("CORE"),
            "- **Type**: pipes",
            "- **Version**: 5.0.0",
            "",
            "Amount pipe.",
            "",
            "### Example",
            FENCE + "html",
            "{{ value | tuiAmount }}",
            FENCE,
            "",
            "# utils/Tokens",
            "- **Package**: " + tick("CORE"),
            "- **Type**: tokens",
            "- **Version**: 5.0.0",
            "",
            "Common tokens.",
        ).joinToString("\n")

    private fun tick(value: String): String = BACKTICK + value + BACKTICK

    private companion object {
        const val BACKTICK = "\u0060"
        const val FENCE = "\u0060\u0060\u0060"
    }
}
