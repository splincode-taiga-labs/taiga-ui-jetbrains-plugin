package org.taigaui.designtokens.documentation

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList

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

    fun testAppearanceChipsRenderInsideCompactHover() {
        val type =
            "\"\" | \"accent\" | \"action-destructive\" | \"action-grayscale\" | " +
                "\"action\" | \"flat-destructive\""
        val owner = directive()
        val input =
            owner.focusedMember(
                TaigaApiProperty("appearance", "[appearance]", type, "Visual appearance"),
                TaigaApiMemberKind.INPUT,
            )
        val panel = TaigaQuickDocumentationPopupPanel(input, compactHover = true)
        val labels = descendants(panel).filterIsInstance<JBLabel>().toList()

        assertTrue(labels.any { it.accessibleContext.accessibleName == "\"\"" })
        assertTrue(labels.any { it.accessibleContext.accessibleName == "\"accent\"" })
        assertFalse(labels.any { it.accessibleContext.accessibleName == type })
    }

    fun testDirectionValuesRenderAsChipsInCompactHover() {
        val type = "\"top\" | \"bottom\" | \"bottom-left\" | \"right-bottom\""
        val panel = requireNotNull(documentationUnionChips(type, width = 160, maxRows = 2))
        val labels = descendants(panel).filterIsInstance<JBLabel>().toList()

        assertTrue(labels.any { it.accessibleContext.accessibleName == "\"top\"" })
        assertTrue(panel.componentCount <= 3)
        assertEquals(type, panel.accessibleContext.accessibleName)
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

    fun testMixedTopLevelUnionsAlsoRenderAsChips() {
        val panel = requireNotNull(documentationUnionChips("'m' | string | undefined"))
        val labels = descendants(panel).filterIsInstance<JBLabel>().toList()

        assertEquals(
            listOf("'m'", "string", "undefined"),
            labels.map { it.accessibleContext.accessibleName },
        )
        assertNotNull(documentationUnionChips("TuiSize | null"))
    }

    @Suppress("UNCHECKED_CAST")
    fun testSearchableApiBrowserUsesChipsInsteadOfPipeSeparatedText() {
        val owner = directive()
        val entity =
            owner.copy(
                entity =
                    owner.entity.copy(
                        inputs = listOf(TaigaApiProperty("size", "[size]", "'s' | 'm' | 'l'", "Button size")),
                    ),
            )
        val browser = TaigaDocumentationApiBrowser(entity, "", {})
        val list = descendants(browser).filterIsInstance<JBList<*>>().single() as JBList<TaigaDocumentationApiRow>
        val component =
            list.cellRenderer.getListCellRendererComponent(
                list,
                list.model.getElementAt(0),
                0,
                true,
                false,
            )
        val chips =
            descendants(component)
                .filterIsInstance<JBLabel>()
                .mapNotNull { it.accessibleContext.accessibleName }
                .toList()

        assertTrue(chips.containsAll(listOf("'s'", "'m'", "'l'")))
        assertFalse(chips.any { it == "'s' | 'm' | 'l'" })
    }

    fun testSimpleTypesAndLiteralsAppearAsSingleChips() {
        val types =
            listOf("Icon", "string", "boolean", "number", "null", "undefined", "true", "false", "\"accent\"", "'s'")
        types.forEach { type ->
            val panel = requireNotNull(documentationUnionChips(type))
            val labels = descendants(panel).filterIsInstance<JBLabel>().toList()

            assertEquals(listOf(type), labels.map { it.accessibleContext.accessibleName })
            assertEquals(type, panel.accessibleContext.accessibleName)
        }
    }

    fun testIncompleteTypeNeverRendersEllipsisAsAnAllowedValue() {
        val type = "'accent' | 'flat' | ..."
        val panel = requireNotNull(documentationUnionChips(type))
        val labels = descendants(panel).filterIsInstance<JBLabel>().toList()

        assertTrue(labels.any { it.accessibleContext.accessibleName == "'accent'" })
        assertTrue(labels.any { it.accessibleContext.accessibleName == "'flat'" })
        assertFalse(labels.any { it.accessibleContext.accessibleName == "..." })
        assertTrue(labels.any { it.text.contains("not specified in the source") })
        assertNull(documentationUnionChips("..."))
        assertNull(documentationUnionChips("…"))
    }

    fun testFullCardDoesNotTruncateLongComplexTypes() {
        val type = "readonly string[] & Record<string, string> & VeryLongNamedTypeWithManyCharacters"
        val owner = directive()
        val property = TaigaApiProperty("iconStart", "[iconStart]", type, "Icon input")
        val panel =
            TaigaQuickDocumentationPopupPanel(
                owner.copy(entity = owner.entity.copy(inputs = listOf(property))),
            )
        val labels = descendants(panel).filterIsInstance<JBLabel>().toList()

        assertTrue(labels.any { it.accessibleContext.accessibleName == type })
        assertFalse(labels.any { it.accessibleContext.accessibleName?.contains(" …") == true })
    }

    fun testCompleteUnionShowsAllValuesEvenBeyondTwoRows() {
        val type = (1..24).joinToString(" | ") { "'value$it'" }
        val panel = requireNotNull(documentationUnionChips(type, width = 155))
        val chips =
            descendants(panel)
                .filterIsInstance<JBLabel>()
                .mapNotNull { it.accessibleContext.accessibleName }
                .toList()

        assertEquals(24, chips.size)
        assertTrue("'value24'" in chips)
        assertFalse(chips.any { it.contains("more") || it == "..." })
    }

    fun testNestedOrQuotedPipesAreNotSplitIntoIndependentChips() {
        assertEquals(
            listOf("Array<'a' | 'b'>", "undefined"),
            documentationUnionAlternatives("Array<'a' | 'b'> | undefined"),
        )
        assertEquals(
            listOf("'left|right'", "'center'"),
            documentationUnionAlternatives("'left|right' | 'center'"),
        )
        assertNull(documentationUnionChips("Map<string, 'left' | 'right'>"))
        assertNotNull(documentationUnionChips("TuiSize"))
        assertNotNull(documentationUnionChips("'m'"))
        assertNull(documentationUnionChips("string || undefined"))
        assertNull(documentationUnionChips("'incomplete | string"))
    }
}
