package org.taigaui.designtokens.documentation

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import javax.swing.JTabbedPane
import javax.swing.JViewport
import javax.swing.SwingUtilities

class TaigaQuickDocumentationAppearanceTest : TaigaDocumentationPopupTestCase() {
    fun testHiddenLongExampleDoesNotExpandTheInputTab() {
        val original = directive()
        val owner = original.copy(entity = original.entity.copy(inputs = original.entity.inputs.take(1)))
        val baseline = TaigaQuickDocumentationPopupPanel(owner).preferredSize.height
        val code = "<button tuiButton>Save</button>\n".repeat(60)
        val documented = owner.copy(entity = owner.entity.copy(example = TaigaExample("html", code)))
        val panel = TaigaQuickDocumentationPopupPanel(documented)
        assertTrue("Hidden example must not inflate the API summary", panel.preferredSize.height <= baseline + 24)
        val tabs = descendants(panel).filterIsInstance<JTabbedPane>().single()
        tabs.selectedIndex = tabs.indexOfTab("Examples")
        assertTrue(panel.preferredSize.height > baseline)
    }

    fun testDirectiveHoverOpensFullCardWhileMembersAndOtherKindsStayCompact() {
        val owner = directive()
        val member = owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT)

        assertFalse(TaigaDocumentationView(owner).isCompactHover(false))
        listOf(member, component(), pipe()).forEach { resolved ->
            assertTrue(TaigaDocumentationView(resolved).isCompactHover(false))
        }
        listOf(owner, member, component(), pipe()).forEach { resolved ->
            assertFalse(TaigaDocumentationView(resolved).isCompactHover(true))
            assertFalse(TaigaDocumentationView(resolved, expanded = true).isCompactHover(false))
            assertFalse(TaigaDocumentationView(resolved, fullApi = true).isCompactHover(false))
            assertFalse(TaigaDocumentationView(resolved, showExample = true).isCompactHover(false))
        }

        val hover = TaigaQuickDocumentationPopupPanel(owner, compactHover = TaigaDocumentationView(owner).isCompactHover(false))
        assertTrue(descendants(hover).any { it is JTabbedPane })
        assertFalse(
            descendants(hover)
                .filterIsInstance<com.intellij.ui.components.labels.LinkLabel<*>>()
                .any { it.text == "Open full card →" },
        )
    }

    fun testNativeHtmlAndTypeScriptHighlightingPreservesFormattingAndEscapesMarkup() {
        val code = "<button\n    tuiButton\n    [size]=\"size\"\n>Save & Close</button>"
        val html = documentationCodeHtml(code)
        assertTrue(html.contains("<span style='color:#"))
        assertTrue(html.contains("&lt;"))
        assertTrue(html.contains("&amp;"))
        assertTrue(html.contains("<br>"))
        assertTrue(html.contains("&nbsp;"))
        assertFalse(html.contains("<button"))
        val colors = Regex("color:(#[0-9a-f]{6})").findAll(html).map { it.groupValues[1] }.toSet()
        assertTrue("HTML tags, attributes and strings need distinct native colors", colors.size > 1)
        assertTrue(documentationCodeHtml("import {TuiButton} from '@taiga-ui/core';").contains("<span"))
        val panel =
            TaigaQuickDocumentationPopupPanel(
                directive().copy(
                    entity = directive().entity.copy(example = TaigaExample("html", code)),
                ),
                showExample = true,
            )
        assertFalse(descendants(panel).any { it is CopyValueButton })
    }

    fun testReferenceCardsInBothThemesKeepEditingSecondaryAndTsApiExplicit() {
        val colors = EditorColorsManager.getInstance()
        val previousScheme = colors.globalScheme
        val previousBright = JBColor.isBright()
        try {
            listOf(false, true).forEach { dark ->
                JBColor.setDark(dark)
                colors.getScheme(if (dark) "Darcula" else "Default")?.let { colors.setGlobalScheme(it) }
                val owner =
                    directive().copy(
                        subject =
                            directive().subject.copy(
                                localDocumentation =
                                    TaigaLocalDocumentation(
                                        selector = "button[tuiButton]",
                                        inputTypes = mapOf("size" to "'xs' | 's' | 'm' | 'l' | 'xl'"),
                                        defaults = listOf(TaigaInputDefault("size", "'m'")),
                                    ),
                            ),
                    )
                val size =
                    owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT).copy(
                        localMember =
                            TaigaLocalApiMember(
                                "size",
                                TaigaApiMemberKind.INPUT,
                                "'xs' | 's' | 'm' | 'l' | 'xl'",
                                owner.subject,
                                declaredAcceptedType = "TuiSizeXS",
                            ),
                        binding = TaigaDocumentationBinding("size", 0, 8, "size=\"m\"", 6, 7, "m", false),
                    )
                val icon =
                    owner.focusedMember(owner.entity.inputs.last(), TaigaApiMemberKind.INPUT).copy(
                        localMember =
                            TaigaLocalApiMember(
                                "iconEnd",
                                TaigaApiMemberKind.INPUT,
                                "string | TemplateRef<unknown>",
                                owner.subject,
                                declaredAcceptedType = "TuiIcon",
                            ),
                    )
                val calendar =
                    owner.copy(
                        entity =
                            owner.entity.copy(
                                title = "Calendar",
                                publicSymbols = setOf("TuiCalendar"),
                                selectors = setOf("tui-calendar"),
                            ),
                        subject =
                            owner.subject.copy(
                                selector = "tui-calendar",
                                publicSymbol = "TuiCalendar",
                                localDocumentation = TaigaLocalDocumentation(selector = "tui-calendar"),
                            ),
                    )
                val output =
                    calendar.focusedMember(
                        TaigaApiProperty("dayClick", "(dayClick)", "TuiDay", "Emits the clicked day."),
                        TaigaApiMemberKind.OUTPUT,
                    )
                val type =
                    owner.copy(
                        entity =
                            owner.entity.copy(
                                kind = TaigaDocKind.TYPE,
                                description = "Available button appearances.",
                            ),
                        subject =
                            owner.subject.copy(
                                selector = null,
                                publicSymbol = "TuiAppearance",
                                localDocumentation = TaigaLocalDocumentation(),
                            ),
                        usage = null,
                        typeDefinition = "'primary' | 'secondary' | 'accent' | 'flat' | 'outline'",
                    )
                listOf(
                    "directive" to owner,
                    "icon-input" to icon,
                    "size-input" to size,
                    "output" to output,
                    "typescript" to owner.copy(usage = null),
                    "type" to type,
                ).forEach { (name, resolved) ->
                    val panel = TaigaQuickDocumentationPopupPanel(resolved)
                    renderAndSave(panel, "reference-$name-${if (dark) "dark" else "light"}")
                    assertReferenceCard(panel, name)
                }
            }
        } finally {
            colors.setGlobalScheme(previousScheme)
            JBColor.setDark(!previousBright)
        }
    }

    private fun assertReferenceCard(
        panel: TaigaQuickDocumentationPopupPanel,
        name: String,
    ) {
        val viewport = descendants(panel).filterIsInstance<JViewport>().first()
        visibleDescendants(panel)
            .filterIsInstance<JBLabel>()
            .filter { it.text == "Usage" || it.text == "Import" }
            .forEach { label ->
                val position = SwingUtilities.convertPoint(label.parent, label.location, viewport)
                assertTrue("Code row labels must stay aligned to the left", position.x <= 1)
            }
        assertFalse(descendants(panel).any { it is CopyValueButton })
        if (name == "typescript") assertFalse(descendants(panel).any { it is JTabbedPane })
        if (name == "size-input") {
            assertTrue(
                descendants(panel).filterIsInstance<JBLabel>().any {
                    it.accessibleContext.accessibleName == "TuiSizeXS"
                },
            )
            assertFalse(descendants(panel).filterIsInstance<javax.swing.JButton>().any { it.text == "Apply" })
        }
    }
}
