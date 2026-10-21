package org.taigaui.designtokens.documentation

import com.intellij.ui.components.JBLabel

class TaigaQuickDocumentationUnionTest : TaigaDocumentationPopupTestCase() {
    fun testLiteralUnionIsRenderedAsSeparateSyntaxHighlightedAlternatives() {
        val type = "\"m\" | \"s\" | \"xs\" | \"l\" | \"xl\" | \"xxl\""
        val panel = requireNotNull(documentationUnionChips(type, width = 150))
        val labels = descendants(panel).filterIsInstance<JBLabel>().toList()

        assertEquals(
            listOf("\"m\"", "\"s\"", "\"xs\"", "\"l\"", "\"xl\"", "\"xxl\""),
            labels.map { it.accessibleContext.accessibleName },
        )
        assertTrue("Literal alternatives should wrap across rows", panel.componentCount > 1)
        assertEquals(type, panel.accessibleContext.accessibleName)
        assertFalse(labels.any { it.text?.contains("…") == true })
    }

    fun testUnionWithEmptyStringRendersChips() {
        val type = "\"\" | \"accent\" | \"action-destructive\" | \"action-grayscale\""
        val panel = requireNotNull(documentationUnionChips(type, width = 180))
        val labels = descendants(panel).filterIsInstance<JBLabel>().toList()

        assertEquals(
            listOf("\"\"", "\"accent\"", "\"action-destructive\"", "\"action-grayscale\""),
            labels.map { it.accessibleContext.accessibleName },
        )
        assertEquals(listOf("", "accent", "action-destructive", "action-grayscale"), finiteStringValues(type))
    }

    fun testCompactHoverLimitsRowsAndMakesRemainingCountVisible() {
        val type = (1..18).joinToString(" | ") { "'value$it'" }
        val panel = requireNotNull(documentationUnionChips(type, width = 125, maxRows = 2))
        val labels = descendants(panel).filterIsInstance<JBLabel>().toList()

        assertTrue(labels.any { it.text?.matches(Regex("""\+\d+ more""")) == true })
        assertTrue(labels.any { it.accessibleContext.accessibleName == "'value1'" })
        assertEquals(type, panel.accessibleContext.accessibleName)
        assertTrue(panel.componentCount <= 3)
    }

    fun testMixedAndComplexTypesKeepTheirOriginalCodePresentation() {
        assertNull(documentationUnionChips("string | undefined"))
        assertNull(documentationUnionChips("TuiSize"))
        assertNull(documentationUnionChips("'m' | string | 'l'"))
        assertNull(documentationUnionChips("'m'"))
    }
}
