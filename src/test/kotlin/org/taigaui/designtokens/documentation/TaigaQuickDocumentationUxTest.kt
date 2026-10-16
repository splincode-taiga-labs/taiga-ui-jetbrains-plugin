package org.taigaui.designtokens.documentation

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import javax.swing.JButton

class TaigaQuickDocumentationUxTest : TaigaDocumentationPopupTestCase() {
    fun testFiniteInputCardHasOneTypeAndOnePairOfValueActions() {
        val owner = directive()
        val type = "'s' | 'm' | 'l' | 'xl' | 'xs'"
        val member =
            owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT).copy(
                subject =
                    owner.subject.copy(
                        localDocumentation = TaigaLocalDocumentation(inputTypes = mapOf("size" to type)),
                    ),
                localMember =
                    TaigaLocalApiMember(
                        "size",
                        TaigaApiMemberKind.INPUT,
                        type,
                        owner.subject,
                        expandedType = "\"s\" | \"m\" | \"l\" | \"xl\" | \"xs\"",
                    ),
                binding = TaigaDocumentationBinding("size", 0, 8, "size=\"s\"", 6, 7, "s", false),
            )
        var writes = 0
        val previousBright = JBColor.isBright()
        try {
            listOf(false, true).forEach { dark ->
                JBColor.setDark(dark)
                val panel =
                    TaigaQuickDocumentationPopupPanel(
                        member,
                        actions =
                            TaigaDocumentationPopupActions(
                                togglePin = {},
                                openOwner = {},
                                navigateToSource = {},
                                showImportFixes = {},
                                applyValue = {
                                    writes++
                                    "Applied $it"
                                },
                            ),
                    )
                assertFalse(labelText(panel).contains("Expanded type"))
                val visible = visibleDescendants(panel).filterIsInstance<JButton>().filter { !it.text.isNullOrEmpty() }
                assertEquals(listOf("Apply"), visible.map { it.text }.toList())
                assertEquals("s", descendants(panel).filterIsInstance<ComboBox<*>>().single().selectedItem)
                assertFalse(button(panel, "Apply").isEnabled)
                assertTrue("A simple input must fit a short card", panel.preferredSize.height <= JBUI.scale(520))
                renderAndSave(panel, "compact-size-${if (dark) "dark" else "light"}")
                selectValue(panel, "m")
                assertEquals(0, writes)
                activate(button(panel, "Apply"), "ENTER")
                assertEquals(1, writes)
                assertFalse(button(panel, "Apply").isEnabled)
                writes = 0
            }
        } finally {
            JBColor.setDark(!previousBright)
        }
    }

    fun testFocusedMemberShowsRelevantTypesAndWarningsWithoutDetails() {
        val owner = directive()
        val member =
            owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT).copy(
                localMember =
                    TaigaLocalApiMember(
                        "size",
                        TaigaApiMemberKind.INPUT,
                        "TuiSize",
                        owner.subject,
                        required = true,
                        expandedType = "'s' | 'm'",
                        valueType = "string",
                        transform = "coerceSize",
                        deprecated = "Use appearance instead",
                    ),
                subject =
                    owner.subject.copy(
                        localDocumentation = TaigaLocalDocumentation(requiredInputs = setOf("size")),
                    ),
            )
        val panel =
            TaigaQuickDocumentationPopupPanel(
                member,
                actions = TaigaDocumentationPopupActions(showImportFixes = {}),
            )
        val text =
            visibleDescendants(panel).filterIsInstance<JBLabel>().joinToString {
                it.accessibleContext.accessibleName ?: it.text
            }
        assertTrue(text.contains("Required input"))
        assertTrue(text.contains("Deprecated"))
        assertTrue(text.contains("Stored value type"))
        assertTrue(text.contains("coerceSize"))
        assertFalse(text.contains("Expanded type"))
        assertFalse(descendants(panel).filterIsInstance<LinkLabel<*>>().any { it.text.startsWith("Details") })
        assertFalse(descendants(panel).any { it is CopyValueButton })
        renderAndSave(panel, "focused-member-facts")
    }

    fun testAllValuesAreSearchableAndStaleFilteringCannotEnableWrites() {
        val owner = directive()
        val values = (1..15).map { "value$it" }
        val type = values.joinToString(" | ") { "'$it'" }
        val member =
            owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT).copy(
                subject =
                    owner.subject.copy(
                        localDocumentation = TaigaLocalDocumentation(inputTypes = mapOf("size" to type)),
                    ),
                binding = TaigaDocumentationBinding("size", 0, 8, "size=\"s\"", 6, 7, "s", false),
            )
        var applied: String? = null
        val panel =
            TaigaQuickDocumentationPopupPanel(
                member,
                actions =
                    TaigaDocumentationPopupActions(applyValue = {
                        applied = it
                        "Applied $it"
                    }),
            )
        val choices = descendants(panel).filterIsInstance<ComboBox<*>>().single()
        assertEquals(15, choices.itemCount)
        val search = descendants(panel).filterIsInstance<JBTextField>().single()
        assertTrue(search.isVisible)
        search.text = "VALUE15"
        assertEquals("value15", choices.selectedItem)
        activate(button(panel, "Apply"), "ENTER")
        assertEquals("value15", applied)
        panel.invalidateContext()
        search.text = "value14"
        val apply = button(panel, "Apply")
        assertFalse(apply.isEnabled)
        activate(apply, "ENTER")
        assertEquals("value15", applied)
        search.text = "unknown"
        assertTrue(labelText(panel).contains("0 of 15 values"))
        assertFalse(button(panel, "Apply").isEnabled)
    }

    fun testDocumentationCardsHaveNoClipboardControls() {
        val owner = directive()
        val member = owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT)
        listOf(owner, member).forEach { resolved ->
            val panel = TaigaQuickDocumentationPopupPanel(resolved)
            assertFalse(descendants(panel).any { it is CopyValueButton })
            assertFalse(descendants(panel).filterIsInstance<JButton>().any { it.text.orEmpty().startsWith("Copy") })
            assertFalse(
                descendants(panel).filterIsInstance<LinkLabel<*>>().any {
                    it.text.startsWith("Details") ||
                        it.text.startsWith("Browse API")
                },
            )
        }
    }

    fun testRefreshProgressBlocksWritesAndKeepsImportHelp() {
        var refreshed = false
        var quickFixes = false
        val panel =
            TaigaQuickDocumentationPopupPanel(
                directive(),
                actions =
                    TaigaDocumentationPopupActions(
                        refresh = { refreshed = true },
                        showImportFixes = { quickFixes = true },
                    ),
            )
        panel.showRefreshProgress(true)
        assertTrue(labelText(panel).contains("Waiting for indexing"))
        assertFalse(descendants(panel).filterIsInstance<JButton>().first { it.text == "Refresh" }.isEnabled)
        descendants(panel)
            .filterIsInstance<LinkLabel<*>>()
            .first { it.text == "Angular quick fixes →" }
            .doClick()
        assertTrue(quickFixes)
    }

    fun testUnavailableExplicitCardExplainsTheTargetAndOffersNativeQuickFixes() {
        var closed = false
        var fixes = false
        val panel = TaigaDocumentationStatusPanel("Waiting for indexing…", { closed = true }, { fixes = true })
        panel.showLoading()
        assertTrue(labelText(panel).contains("Loading"))
        panel.showUnavailable()
        assertTrue(labelText(panel).contains("No installed Taiga UI API"))
        val quickFixes = descendants(panel).filterIsInstance<JButton>().first { it.text == "Angular quick fixes" }
        assertTrue(quickFixes.isVisible)
        activate(quickFixes, "ENTER")
        assertTrue(fixes)
        activate(panel, "ESCAPE")
        assertTrue(closed)
    }

    fun testApiSelectionAndCompleteDescriptionAreAccessible() {
        val owner = directive()
        val description = "Long documentation. ".repeat(40) + "Full description ends here."
        val documentedOwner = owner.copy(entity = owner.entity.copy(description = description))
        val panel =
            TaigaQuickDocumentationPopupPanel(
                documentedOwner,
                apiQuery = "",
                selectedMember = "INPUT:iconEnd",
            )
        val list = descendants(panel).filterIsInstance<JBList<*>>().single()
        assertEquals("iconEnd", (list.selectedValue as TaigaDocumentationApiRow).property.name)
        assertFalse(labelText(panel).contains("Full description ends here."))
        descendants(panel).filterIsInstance<LinkLabel<*>>().first { it.text == "Show full description" }.doClick()
        assertTrue(labelText(panel).contains("Full description ends here."))
        renderAndSave(panel, "expanded-description")
    }

    fun testLongSharedBindingCardsFitBothThemesAtEverySupportedScale() {
        val owner = directive()
        val type = "VeryLongInstalledInputType | ".repeat(30) + "string"
        val property =
            owner.entity.inputs
                .first()
                .copy(documentedType = type)
        val first =
            owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT).copy(
                property = property,
            )
        val secondSubject = first.subject.copy(publicSymbol = "TuiOtherReceiver")
        val second = first.copy(subject = secondSubject)
        val shared = first.copy(receivers = listOf(first, second))
        val previousBright = JBColor.isBright()
        val previousScale = JBUIScale.scale(1f)
        try {
            listOf(1f, 1.25f, 2f).forEach { scale ->
                JBUIScale.setUserScaleFactorForTest(scale)
                assertEquals(scale, JBUIScale.scale(1f))
                listOf(false, true).forEach { dark ->
                    renderLongSharedBindingCard(shared, scale, dark)
                }
            }
        } finally {
            JBUIScale.setUserScaleFactorForTest(previousScale)
            JBColor.setDark(!previousBright)
        }
    }

    private fun renderLongSharedBindingCard(
        shared: TaigaResolvedDocumentation.Member,
        scale: Float,
        dark: Boolean,
    ) {
        JBColor.setDark(dark)
        val panel =
            TaigaQuickDocumentationPopupPanel(
                shared,
                actions = TaigaDocumentationPopupActions(showImportFixes = {}),
            )
        assertTrue("Long types must fit the card", panel.preferredSize.width <= JBUI.scale(580))
        assertFalse(descendants(panel).any { it is CopyValueButton })
        val theme = if (dark) "dark" else "light"
        renderAndSave(panel, "long-receivers-$theme-${(scale * 100).toInt()}")
    }
}
