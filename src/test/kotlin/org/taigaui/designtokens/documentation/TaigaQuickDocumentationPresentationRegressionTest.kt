package org.taigaui.designtokens.documentation

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.labels.LinkLabel
import javax.swing.JTabbedPane

class TaigaQuickDocumentationPresentationRegressionTest : TaigaDocumentationPopupTestCase() {
    fun testFlattenedButtonExampleGetsAttributeIndentationWithoutSplittingQuotedExpressions() {
        val code =
            """<button tuiButton type="button" [disabled]="count > 2" """ +
                """[size]="size" [iconEnd]="icons.iconEnd"> Button </button>"""
        val formatted = formattedDocumentationExample(code)
        assertTrue(formatted.startsWith("<button\n    tuiButton\n    type=\"button\""))
        assertTrue(formatted.contains("    [disabled]=\"count > 2\"\n"))
        assertTrue(formatted.endsWith(">\n    Button\n</button>"))
        assertEquals(formatted, formattedDocumentationExample(formatted))
        val nested = """<button tuiButton [size]="size"><span>Save</span></button>"""
        assertEquals(nested, formattedDocumentationExample(nested))
        val owner = directive()
        val panel =
            TaigaQuickDocumentationPopupPanel(
                owner.copy(entity = owner.entity.copy(example = TaigaExample("html", code))),
            )
        val tabs = descendants(panel).filterIsInstance<JTabbedPane>().single()
        tabs.selectedIndex = tabs.indexOfTab("Examples")
        renderAndSave(panel, "formatted-button-example")
    }

    fun testAllInputsUseVerticalRowsAndEmptyOutputTabIsHidden() {
        val owner = directive()
        val inputs = (1..7).map { TaigaApiProperty("input$it", "[input$it]", "string", "Description $it") }
        val panel =
            TaigaQuickDocumentationPopupPanel(
                owner.copy(entity = owner.entity.copy(inputs = inputs, outputs = emptyList())),
            )
        renderAndSave(panel, "vertical-inputs")
        val tabs = descendants(panel).filterIsInstance<JTabbedPane>().single()
        assertEquals("Inputs (7)", tabs.getTitleAt(0))
        assertFalse((0 until tabs.tabCount).any { tabs.getTitleAt(it).startsWith("Outputs") })
        val names = descendants(panel).filterIsInstance<LinkLabel<*>>().filter { it.text.startsWith("input") }.toList()
        assertEquals(7, names.size)
        val labels = descendants(panel).filterIsInstance<JBLabel>().map { it.text }.toList()
        assertFalse(labels.any { it in listOf("Name", "Type", "Description") })
        assertTrue(labelText(panel).contains("Description 7"))
        assertFalse(descendants(panel).any { it is CopyValueButton })
    }

    fun testApiNameTypeAndDefaultShareTheSameLeftEdge() {
        val panel = TaigaQuickDocumentationPopupPanel(directive())
        renderAndSave(panel, "vertical-default-alignment")
        val name = descendants(panel).filterIsInstance<LinkLabel<*>>().first { it.text == "size" }
        val type =
            descendants(panel).filterIsInstance<JBLabel>().first {
                it.accessibleContext.accessibleName ==
                    "TuiSize"
            }
        val default =
            descendants(panel).filterIsInstance<JBLabel>().first {
                it.text ==
                    "Default from TUI_BUTTON_OPTIONS"
            }
        val nameX =
            javax.swing.SwingUtilities
                .convertPoint(name.parent, name.location, panel)
                .x
        assertEquals(
            nameX,
            javax.swing.SwingUtilities
                .convertPoint(type.parent, type.location, panel)
                .x,
        )
        assertEquals(
            nameX,
            javax.swing.SwingUtilities
                .convertPoint(default.parent, default.location, panel)
                .x,
        )
    }

    fun testHoverHighlightIsScopedAndRemovedWithoutChangingSource() {
        myFixture.configureByText("component.html", "<button tuiButton [iconEnd]=\"icon\">Save</button>")
        val editor = myFixture.editor
        val before = editor.document.text
        val start = before.indexOf("[iconEnd]")
        val highlight = TaigaQuickDocumentationUnderline()
        val initial = editor.markupModel.allHighlighters.toSet()
        highlight.show(editor, start, start + 9)
        val applied = (editor.markupModel.allHighlighters.toSet() - initial).single()
        assertEquals(start, applied.startOffset)
        assertEquals(start + 9, applied.endOffset)
        assertNotNull(applied.textAttributes?.backgroundColor)
        assertFalse(applied.textAttributes?.effectType == com.intellij.openapi.editor.markup.EffectType.LINE_UNDERSCORE)
        highlight.show(editor, start, start + 9)
        assertEquals(initial.size + 1, editor.markupModel.allHighlighters.size)
        highlight.clear()
        assertEquals(initial, editor.markupModel.allHighlighters.toSet())
        assertEquals(before, editor.document.text)
    }

    fun testInputHoverPointerRendersWithCard() {
        val owner = directive()
        val member = owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT)
        val panel = TaigaQuickDocumentationPopupPanel(member, hoverPointer = true)
        panel.alignHoverPointer(140)
        renderAndSave(panel, "input-hover-pointer")
    }

    fun testHostDirectiveDescriptionsEnrichOnlyInstalledMembersAndKeepLocalTypes() {
        val original = directive()
        val appearanceOwner = TaigaDocumentationSubject(null, "TuiAppearance", "@taiga-ui/core")
        val local =
            original.subject.copy(
                localDocumentation =
                    TaigaLocalDocumentation(
                        angularResolved = true,
                        members =
                            listOf(
                                TaigaLocalApiMember(
                                    "appearance",
                                    TaigaApiMemberKind.INPUT,
                                    "'primary' | 'accent'",
                                    appearanceOwner,
                                ),
                            ),
                    ),
            )
        val appearance =
            original.entity.copy(
                sectionId = "directives/appearance",
                publicSymbols = setOf("TuiAppearance"),
                inputs = listOf(TaigaApiProperty("appearance", "[appearance]", "string", "Appearance of the element")),
            )
        val snapshot =
            TaigaDocsSnapshot(
                TaigaUiProjectContext("5.18.0", 5, "@taiga-ui/core", setOf("@taiga-ui/core"), "fixture"),
                TaigaDocsIndex(
                    requireNotNull(TaigaDocsSources.forMajor(5)),
                    listOf(original.entity.copy(inputs = emptyList()), appearance),
                ),
            )
        val resolved = resolveDocumentation(TaigaDocumentationRequest.Entity(listOf(local), 0, 1), snapshot)
        assertEquals(1, resolved?.entity?.inputs?.size)
        assertEquals(
            "'primary' | 'accent'",
            resolved
                ?.entity
                ?.inputs
                ?.single()
                ?.documentedType,
        )
        assertEquals(
            "Appearance of the element",
            resolved
                ?.entity
                ?.inputs
                ?.single()
                ?.description,
        )
    }
}
