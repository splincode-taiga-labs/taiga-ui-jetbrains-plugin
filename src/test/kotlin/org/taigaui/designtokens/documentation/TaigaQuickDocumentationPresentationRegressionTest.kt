package org.taigaui.designtokens.documentation

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.JBUI
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import kotlin.time.Duration.Companion.milliseconds

class TaigaQuickDocumentationPresentationRegressionTest : TaigaDocumentationPopupTestCase() {
    fun testUsageChoosesOnlyTheSelectorMatchingTheActualHostTag() {
        val selectors = "tui-avatar,button[tuiAvatar],a[tuiAvatar]"

        assertEquals(
            """<tui-avatar [size]="value">...</tui-avatar>""",
            documentationMemberUsage("tui-avatar", selectors, "size", TaigaApiMemberKind.INPUT),
        )
        assertEquals(
            """<button tuiAvatar [size]="value">...</button>""",
            documentationMemberUsage("button", selectors, "size", TaigaApiMemberKind.INPUT),
        )
        assertEquals(
            """<a tuiAvatar (loaded)="handler(${'
            documentationMemberUsage("a", selectors, "loaded", TaigaApiMemberKind.OUTPUT),
        )
        assertEquals(
            """<div [size]="value">...</div>""",
            documentationMemberUsage("div", selectors, "size", TaigaApiMemberKind.INPUT),
        )
    }

    fun testUsageKeepsTheDirectiveAttributeWithoutDuplicatingTheTagName() {
        assertEquals(
            """<button tuiButton [size]="value">...</button>""",
            documentationMemberUsage(
                "button",
                "a[tuiButton],button[tuiButton],label[tuiButton]",
                "size",
                TaigaApiMemberKind.INPUT,
            ),
        )
        assertEquals(
            """<button tuiButton [size]="value">...</button>""",
            documentationMemberUsage("button", "tuiButton", "size", TaigaApiMemberKind.INPUT),
        )
    }

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

    fun testHoverPointerDoesNotPaintAnEditorBackgroundStripeAcrossTheCard() {
        val owner = directive()
        val member = owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT)
        val panel = TaigaQuickDocumentationPopupPanel(member, hoverPointer = true)
        panel.alignHoverPointer(140)
        panel.size = panel.preferredSize
        panel.doLayout()

        val image = java.awt.image.BufferedImage(panel.width, panel.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            panel.paint(graphics)
        } finally {
            graphics.dispose()
        }

        // The top inset is part of the card, not a full-width stripe painted in the editor color.
        assertEquals(panel.background.rgb, image.getRGB(panel.width - 16, 3))
    }

    fun testHoverSwitchUsesShorterDwellWithoutMovingOnTinyGeometryChanges() {
        assertEquals(850.milliseconds, taigaDocumentationHoverDelay(switching = false))
        assertEquals(200.milliseconds, taigaDocumentationHoverDelay(switching = true))
        assertFalse(shouldRepositionTaigaHoverCard(java.awt.Point(100, 100), java.awt.Point(102, 98)))
        assertTrue(shouldRepositionTaigaHoverCard(java.awt.Point(100, 100), java.awt.Point(116, 100)))
    }

    fun testHoverCardHostKeepsItsContainerAndReplacesOnlyTheContent() {
        val first = TaigaQuickDocumentationPopupPanel(directive())
        val host = TaigaDocumentationPopupHost(first)
        val next = TaigaQuickDocumentationPopupPanel(component())
        assertSame(first, host.card)
        assertSame(host, first.parent)

        host.showCard(next)
        assertSame(next, host.card)
        assertSame(host, next.parent)
        assertNull(first.parent)
        assertEquals(1, host.componentCount)

        host.showCard(next)
        assertEquals(1, host.componentCount)
        host.stopTransition()
    }

    fun testHoverSwitchResizesForFullDirectiveAndRestoresCompactSize() {
        val small = TaigaQuickDocumentationPopupPanel(pipe(), compactHover = true, hoverPointer = true)
        val large = TaigaQuickDocumentationPopupPanel(directive(), hoverPointer = true)
        val host = TaigaDocumentationPopupHost(small, stabilizesHoverSize = true)
        val initialSize = java.awt.Dimension(host.preferredSize)

        assertTrue(initialSize.width >= JBUI.scale(380))
        assertTrue(initialSize.height in JBUI.scale(220)..JBUI.scale(300))
        host.showCard(large)
        assertEquals(large.preferredSize, host.preferredSize)
        assertEquals(1, host.componentCount)

        host.size = host.preferredSize
        host.doLayout()
        assertEquals(host.preferredSize, large.size)

        host.showCard(small)
        assertEquals(initialSize, host.preferredSize)
        host.stopTransition()
    }

    fun testDirectiveFirstHoverCanSwitchToCompactInputWithoutKeepingFullHeight() {
        val directive = TaigaQuickDocumentationPopupPanel(directive(), hoverPointer = true)
        val compact = TaigaQuickDocumentationPopupPanel(pipe(), compactHover = true, hoverPointer = true)
        val host = TaigaDocumentationPopupHost(directive, stabilizesHoverSize = true)

        assertEquals(directive.preferredSize, host.preferredSize)
        host.showCard(compact)
        assertTrue(host.preferredSize.height in JBUI.scale(220)..JBUI.scale(300))
        assertTrue(host.preferredSize.width >= JBUI.scale(380))
        assertSame(compact, host.card)
        host.stopTransition()
    }

    fun testComponentFullHoverRestoresCompactInputViewportWhenSwitching() {
        val compact = TaigaQuickDocumentationPopupPanel(pipe(), compactHover = true, hoverPointer = true)
        val component = TaigaQuickDocumentationPopupPanel(component(), hoverPointer = true)
        val host = TaigaDocumentationPopupHost(compact, stabilizesHoverSize = true)
        val compactSize = java.awt.Dimension(host.preferredSize)

        host.showCard(component)
        assertEquals(component.preferredSize, host.preferredSize)
        assertSame(component, host.card)

        host.showCard(compact)
        assertEquals(compactSize, host.preferredSize)
        host.stopTransition()
    }

    fun testShortHoverContentHidesScrollbarWithStableOuterSize() {
        val card = TaigaQuickDocumentationPopupPanel(pipe(), compactHover = true, hoverPointer = true)
        val host = TaigaDocumentationPopupHost(card, stabilizesHoverSize = true)
        val before = java.awt.Dimension(host.preferredSize)
        host.size = before
        host.doLayout()
        card.doLayout()
        val scroll = descendants(card).filterIsInstance<JBScrollPane>().single()
        scroll.doLayout()
        card.updateHoverScrollbarVisibility()

        assertEquals(
            JScrollPane.VERTICAL_SCROLLBAR_NEVER,
            taigaHoverScrollbarPolicy(scroll.viewport.extentSize.height, scroll.viewport.extentSize.height),
        )
        assertEquals(JScrollPane.VERTICAL_SCROLLBAR_NEVER, scroll.verticalScrollBarPolicy)
        assertEquals(before, host.preferredSize)
        host.stopTransition()
    }

    fun testLongHoverContentRemainsScrollableWithoutResizingHost() {
        val card = TaigaQuickDocumentationPopupPanel(directive(), compactHover = true, hoverPointer = true)
        val host = TaigaDocumentationPopupHost(card, stabilizesHoverSize = true)
        val before = java.awt.Dimension(host.preferredSize)
        host.size = before
        host.doLayout()
        card.doLayout()
        val scroll = descendants(card).filterIsInstance<JBScrollPane>().single()
        scroll.doLayout()
        assertEquals(
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            taigaHoverScrollbarPolicy(
                scroll.viewport.extentSize.height + JBUI.scale(80),
                scroll.viewport.extentSize.height,
            ),
        )
        assertEquals(before, host.preferredSize)
        host.stopTransition()
    }

    fun testFullDocumentationHostStillUsesNaturalContentSize() {
        val initial = TaigaQuickDocumentationPopupPanel(pipe(), compactHover = true)
        val expanded = TaigaQuickDocumentationPopupPanel(directive())
        val host = TaigaDocumentationPopupHost(initial)
        host.showCard(expanded)
        assertEquals(expanded.preferredSize, host.preferredSize)
    }

    fun testHoverPlacementPrefersRightWithoutCoveringTheAttribute() {
        val target = java.awt.Rectangle(220, 160, 75, 20)
        val size = java.awt.Dimension(360, 240)
        val placement =
            calculateTaigaHoverPopupPlacement(target, size, java.awt.Rectangle(0, 0, 1200, 800), 12)
        assertEquals(TaigaHoverPointerSide.LEFT, placement.pointerSide)
        assertEquals(target.x + target.width + 12, placement.location.x)
        assertTrue(placement.location.y <= target.y + target.height)
    }

    fun testHoverPlacementFlipsLeftNearTheRightScreenEdge() {
        val target = java.awt.Rectangle(900, 380, 80, 22)
        val size = java.awt.Dimension(360, 240)
        val placement =
            calculateTaigaHoverPopupPlacement(target, size, java.awt.Rectangle(0, 0, 1200, 800), 12)
        assertEquals(TaigaHoverPointerSide.RIGHT, placement.pointerSide)
        assertTrue(placement.location.x + size.width < target.x)
    }

    fun testHoverPlacementFallsBelowOnNarrowScreens() {
        val target = java.awt.Rectangle(230, 40, 70, 20)
        val size = java.awt.Dimension(370, 220)
        val placement =
            calculateTaigaHoverPopupPlacement(target, size, java.awt.Rectangle(0, 0, 450, 700), 12)
        assertEquals(TaigaHoverPointerSide.NONE, placement.pointerSide)
        assertTrue(placement.location.y >= target.y + target.height)
    }

    fun testHoverPointerUsesTransparentGutterOutsideTheCard() {
        val owner = directive()
        val member = owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT)
        val panel = TaigaQuickDocumentationPopupPanel(member, hoverPointer = true)
        panel.alignHoverPointer(140)
        panel.size = panel.preferredSize
        panel.doLayout()
        val image = java.awt.image.BufferedImage(panel.width, panel.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            panel.paint(graphics)
        } finally {
            graphics.dispose()
        }
        assertEquals(0, image.getRGB(3, 3) ushr 24)
        assertEquals(panel.background.rgb, image.getRGB(16, 30))
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
}event)">...</a>""",
            documentationMemberUsage("a", selectors, "loaded", TaigaApiMemberKind.OUTPUT),
        )
        assertEquals(
            """<div [size]="value">...</div>""",
            documentationMemberUsage("div", selectors, "size", TaigaApiMemberKind.INPUT),
        )
    }

    fun testUsageKeepsTheDirectiveAttributeWithoutDuplicatingTheTagName() {
        assertEquals(
            """<button tuiButton [size]="value">...</button>""",
            documentationMemberUsage(
                "button",
                "a[tuiButton],button[tuiButton],label[tuiButton]",
                "size",
                TaigaApiMemberKind.INPUT,
            ),
        )
        assertEquals(
            """<button tuiButton [size]="value">...</button>""",
            documentationMemberUsage("button", "tuiButton", "size", TaigaApiMemberKind.INPUT),
        )
    }

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

    fun testHoverPointerDoesNotPaintAnEditorBackgroundStripeAcrossTheCard() {
        val owner = directive()
        val member = owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT)
        val panel = TaigaQuickDocumentationPopupPanel(member, hoverPointer = true)
        panel.alignHoverPointer(140)
        panel.size = panel.preferredSize
        panel.doLayout()

        val image = java.awt.image.BufferedImage(panel.width, panel.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            panel.paint(graphics)
        } finally {
            graphics.dispose()
        }

        // The top inset is part of the card, not a full-width stripe painted in the editor color.
        assertEquals(panel.background.rgb, image.getRGB(panel.width - 16, 3))
    }

    fun testHoverSwitchUsesShorterDwellWithoutMovingOnTinyGeometryChanges() {
        assertEquals(850.milliseconds, taigaDocumentationHoverDelay(switching = false))
        assertEquals(200.milliseconds, taigaDocumentationHoverDelay(switching = true))
        assertFalse(shouldRepositionTaigaHoverCard(java.awt.Point(100, 100), java.awt.Point(102, 98)))
        assertTrue(shouldRepositionTaigaHoverCard(java.awt.Point(100, 100), java.awt.Point(116, 100)))
    }

    fun testHoverCardHostKeepsItsContainerAndReplacesOnlyTheContent() {
        val first = TaigaQuickDocumentationPopupPanel(directive())
        val host = TaigaDocumentationPopupHost(first)
        val next = TaigaQuickDocumentationPopupPanel(component())
        assertSame(first, host.card)
        assertSame(host, first.parent)

        host.showCard(next)
        assertSame(next, host.card)
        assertSame(host, next.parent)
        assertNull(first.parent)
        assertEquals(1, host.componentCount)

        host.showCard(next)
        assertEquals(1, host.componentCount)
        host.stopTransition()
    }

    fun testHoverSwitchResizesForFullDirectiveAndRestoresCompactSize() {
        val small = TaigaQuickDocumentationPopupPanel(pipe(), compactHover = true, hoverPointer = true)
        val large = TaigaQuickDocumentationPopupPanel(directive(), hoverPointer = true)
        val host = TaigaDocumentationPopupHost(small, stabilizesHoverSize = true)
        val initialSize = java.awt.Dimension(host.preferredSize)

        assertTrue(initialSize.width >= JBUI.scale(380))
        assertTrue(initialSize.height in JBUI.scale(220)..JBUI.scale(300))
        host.showCard(large)
        assertEquals(large.preferredSize, host.preferredSize)
        assertEquals(1, host.componentCount)

        host.size = host.preferredSize
        host.doLayout()
        assertEquals(host.preferredSize, large.size)

        host.showCard(small)
        assertEquals(initialSize, host.preferredSize)
        host.stopTransition()
    }

    fun testDirectiveFirstHoverCanSwitchToCompactInputWithoutKeepingFullHeight() {
        val directive = TaigaQuickDocumentationPopupPanel(directive(), hoverPointer = true)
        val compact = TaigaQuickDocumentationPopupPanel(pipe(), compactHover = true, hoverPointer = true)
        val host = TaigaDocumentationPopupHost(directive, stabilizesHoverSize = true)

        assertEquals(directive.preferredSize, host.preferredSize)
        host.showCard(compact)
        assertTrue(host.preferredSize.height in JBUI.scale(220)..JBUI.scale(300))
        assertTrue(host.preferredSize.width >= JBUI.scale(380))
        assertSame(compact, host.card)
        host.stopTransition()
    }

    fun testComponentFullHoverRestoresCompactInputViewportWhenSwitching() {
        val compact = TaigaQuickDocumentationPopupPanel(pipe(), compactHover = true, hoverPointer = true)
        val component = TaigaQuickDocumentationPopupPanel(component(), hoverPointer = true)
        val host = TaigaDocumentationPopupHost(compact, stabilizesHoverSize = true)
        val compactSize = java.awt.Dimension(host.preferredSize)

        host.showCard(component)
        assertEquals(component.preferredSize, host.preferredSize)
        assertSame(component, host.card)

        host.showCard(compact)
        assertEquals(compactSize, host.preferredSize)
        host.stopTransition()
    }

    fun testShortHoverContentHidesScrollbarWithStableOuterSize() {
        val card = TaigaQuickDocumentationPopupPanel(pipe(), compactHover = true, hoverPointer = true)
        val host = TaigaDocumentationPopupHost(card, stabilizesHoverSize = true)
        val before = java.awt.Dimension(host.preferredSize)
        host.size = before
        host.doLayout()
        card.doLayout()
        val scroll = descendants(card).filterIsInstance<JBScrollPane>().single()
        scroll.doLayout()
        card.updateHoverScrollbarVisibility()

        assertEquals(
            JScrollPane.VERTICAL_SCROLLBAR_NEVER,
            taigaHoverScrollbarPolicy(scroll.viewport.extentSize.height, scroll.viewport.extentSize.height),
        )
        assertEquals(JScrollPane.VERTICAL_SCROLLBAR_NEVER, scroll.verticalScrollBarPolicy)
        assertEquals(before, host.preferredSize)
        host.stopTransition()
    }

    fun testLongHoverContentRemainsScrollableWithoutResizingHost() {
        val card = TaigaQuickDocumentationPopupPanel(directive(), compactHover = true, hoverPointer = true)
        val host = TaigaDocumentationPopupHost(card, stabilizesHoverSize = true)
        val before = java.awt.Dimension(host.preferredSize)
        host.size = before
        host.doLayout()
        card.doLayout()
        val scroll = descendants(card).filterIsInstance<JBScrollPane>().single()
        scroll.doLayout()
        assertEquals(
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            taigaHoverScrollbarPolicy(
                scroll.viewport.extentSize.height + JBUI.scale(80),
                scroll.viewport.extentSize.height,
            ),
        )
        assertEquals(before, host.preferredSize)
        host.stopTransition()
    }

    fun testFullDocumentationHostStillUsesNaturalContentSize() {
        val initial = TaigaQuickDocumentationPopupPanel(pipe(), compactHover = true)
        val expanded = TaigaQuickDocumentationPopupPanel(directive())
        val host = TaigaDocumentationPopupHost(initial)
        host.showCard(expanded)
        assertEquals(expanded.preferredSize, host.preferredSize)
    }

    fun testHoverPlacementPrefersRightWithoutCoveringTheAttribute() {
        val target = java.awt.Rectangle(220, 160, 75, 20)
        val size = java.awt.Dimension(360, 240)
        val placement =
            calculateTaigaHoverPopupPlacement(target, size, java.awt.Rectangle(0, 0, 1200, 800), 12)
        assertEquals(TaigaHoverPointerSide.LEFT, placement.pointerSide)
        assertEquals(target.x + target.width + 12, placement.location.x)
        assertTrue(placement.location.y <= target.y + target.height)
    }

    fun testHoverPlacementFlipsLeftNearTheRightScreenEdge() {
        val target = java.awt.Rectangle(900, 380, 80, 22)
        val size = java.awt.Dimension(360, 240)
        val placement =
            calculateTaigaHoverPopupPlacement(target, size, java.awt.Rectangle(0, 0, 1200, 800), 12)
        assertEquals(TaigaHoverPointerSide.RIGHT, placement.pointerSide)
        assertTrue(placement.location.x + size.width < target.x)
    }

    fun testHoverPlacementFallsBelowOnNarrowScreens() {
        val target = java.awt.Rectangle(230, 40, 70, 20)
        val size = java.awt.Dimension(370, 220)
        val placement =
            calculateTaigaHoverPopupPlacement(target, size, java.awt.Rectangle(0, 0, 450, 700), 12)
        assertEquals(TaigaHoverPointerSide.NONE, placement.pointerSide)
        assertTrue(placement.location.y >= target.y + target.height)
    }

    fun testHoverPointerUsesTransparentGutterOutsideTheCard() {
        val owner = directive()
        val member = owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT)
        val panel = TaigaQuickDocumentationPopupPanel(member, hoverPointer = true)
        panel.alignHoverPointer(140)
        panel.size = panel.preferredSize
        panel.doLayout()
        val image = java.awt.image.BufferedImage(panel.width, panel.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            panel.paint(graphics)
        } finally {
            graphics.dispose()
        }
        assertEquals(0, image.getRGB(3, 3) ushr 24)
        assertEquals(panel.background.rgb, image.getRGB(16, 30))
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
}event)">...</a>""",
            documentationMemberUsage("a", selectors, "loaded", TaigaApiMemberKind.OUTPUT),
        )
        assertEquals(
            """<div [size]="value">...</div>""",
            documentationMemberUsage("div", selectors, "size", TaigaApiMemberKind.INPUT),
        )
    }

    fun testUsageKeepsTheDirectiveAttributeWithoutDuplicatingTheTagName() {
        assertEquals(
            """<button tuiButton [size]="value">...</button>""",
            documentationMemberUsage(
                "button",
                "a[tuiButton],button[tuiButton],label[tuiButton]",
                "size",
                TaigaApiMemberKind.INPUT,
            ),
        )
        assertEquals(
            """<button tuiButton [size]="value">...</button>""",
            documentationMemberUsage("button", "tuiButton", "size", TaigaApiMemberKind.INPUT),
        )
    }

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

    fun testHoverPointerDoesNotPaintAnEditorBackgroundStripeAcrossTheCard() {
        val owner = directive()
        val member = owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT)
        val panel = TaigaQuickDocumentationPopupPanel(member, hoverPointer = true)
        panel.alignHoverPointer(140)
        panel.size = panel.preferredSize
        panel.doLayout()

        val image = java.awt.image.BufferedImage(panel.width, panel.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            panel.paint(graphics)
        } finally {
            graphics.dispose()
        }

        // The top inset is part of the card, not a full-width stripe painted in the editor color.
        assertEquals(panel.background.rgb, image.getRGB(panel.width - 16, 3))
    }

    fun testHoverSwitchUsesShorterDwellWithoutMovingOnTinyGeometryChanges() {
        assertEquals(850.milliseconds, taigaDocumentationHoverDelay(switching = false))
        assertEquals(200.milliseconds, taigaDocumentationHoverDelay(switching = true))
        assertFalse(shouldRepositionTaigaHoverCard(java.awt.Point(100, 100), java.awt.Point(102, 98)))
        assertTrue(shouldRepositionTaigaHoverCard(java.awt.Point(100, 100), java.awt.Point(116, 100)))
    }

    fun testHoverCardHostKeepsItsContainerAndReplacesOnlyTheContent() {
        val first = TaigaQuickDocumentationPopupPanel(directive())
        val host = TaigaDocumentationPopupHost(first)
        val next = TaigaQuickDocumentationPopupPanel(component())
        assertSame(first, host.card)
        assertSame(host, first.parent)

        host.showCard(next)
        assertSame(next, host.card)
        assertSame(host, next.parent)
        assertNull(first.parent)
        assertEquals(1, host.componentCount)

        host.showCard(next)
        assertEquals(1, host.componentCount)
        host.stopTransition()
    }

    fun testHoverSwitchResizesForFullDirectiveAndRestoresCompactSize() {
        val small = TaigaQuickDocumentationPopupPanel(pipe(), compactHover = true, hoverPointer = true)
        val large = TaigaQuickDocumentationPopupPanel(directive(), hoverPointer = true)
        val host = TaigaDocumentationPopupHost(small, stabilizesHoverSize = true)
        val initialSize = java.awt.Dimension(host.preferredSize)

        assertTrue(initialSize.width >= JBUI.scale(380))
        assertTrue(initialSize.height in JBUI.scale(220)..JBUI.scale(300))
        host.showCard(large)
        assertEquals(large.preferredSize, host.preferredSize)
        assertEquals(1, host.componentCount)

        host.size = host.preferredSize
        host.doLayout()
        assertEquals(host.preferredSize, large.size)

        host.showCard(small)
        assertEquals(initialSize, host.preferredSize)
        host.stopTransition()
    }

    fun testDirectiveFirstHoverCanSwitchToCompactInputWithoutKeepingFullHeight() {
        val directive = TaigaQuickDocumentationPopupPanel(directive(), hoverPointer = true)
        val compact = TaigaQuickDocumentationPopupPanel(pipe(), compactHover = true, hoverPointer = true)
        val host = TaigaDocumentationPopupHost(directive, stabilizesHoverSize = true)

        assertEquals(directive.preferredSize, host.preferredSize)
        host.showCard(compact)
        assertTrue(host.preferredSize.height in JBUI.scale(220)..JBUI.scale(300))
        assertTrue(host.preferredSize.width >= JBUI.scale(380))
        assertSame(compact, host.card)
        host.stopTransition()
    }

    fun testComponentFullHoverRestoresCompactInputViewportWhenSwitching() {
        val compact = TaigaQuickDocumentationPopupPanel(pipe(), compactHover = true, hoverPointer = true)
        val component = TaigaQuickDocumentationPopupPanel(component(), hoverPointer = true)
        val host = TaigaDocumentationPopupHost(compact, stabilizesHoverSize = true)
        val compactSize = java.awt.Dimension(host.preferredSize)

        host.showCard(component)
        assertEquals(component.preferredSize, host.preferredSize)
        assertSame(component, host.card)

        host.showCard(compact)
        assertEquals(compactSize, host.preferredSize)
        host.stopTransition()
    }

    fun testShortHoverContentHidesScrollbarWithStableOuterSize() {
        val card = TaigaQuickDocumentationPopupPanel(pipe(), compactHover = true, hoverPointer = true)
        val host = TaigaDocumentationPopupHost(card, stabilizesHoverSize = true)
        val before = java.awt.Dimension(host.preferredSize)
        host.size = before
        host.doLayout()
        card.doLayout()
        val scroll = descendants(card).filterIsInstance<JBScrollPane>().single()
        scroll.doLayout()
        card.updateHoverScrollbarVisibility()

        assertEquals(
            JScrollPane.VERTICAL_SCROLLBAR_NEVER,
            taigaHoverScrollbarPolicy(scroll.viewport.extentSize.height, scroll.viewport.extentSize.height),
        )
        assertEquals(JScrollPane.VERTICAL_SCROLLBAR_NEVER, scroll.verticalScrollBarPolicy)
        assertEquals(before, host.preferredSize)
        host.stopTransition()
    }

    fun testLongHoverContentRemainsScrollableWithoutResizingHost() {
        val card = TaigaQuickDocumentationPopupPanel(directive(), compactHover = true, hoverPointer = true)
        val host = TaigaDocumentationPopupHost(card, stabilizesHoverSize = true)
        val before = java.awt.Dimension(host.preferredSize)
        host.size = before
        host.doLayout()
        card.doLayout()
        val scroll = descendants(card).filterIsInstance<JBScrollPane>().single()
        scroll.doLayout()
        assertEquals(
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            taigaHoverScrollbarPolicy(
                scroll.viewport.extentSize.height + JBUI.scale(80),
                scroll.viewport.extentSize.height,
            ),
        )
        assertEquals(before, host.preferredSize)
        host.stopTransition()
    }

    fun testFullDocumentationHostStillUsesNaturalContentSize() {
        val initial = TaigaQuickDocumentationPopupPanel(pipe(), compactHover = true)
        val expanded = TaigaQuickDocumentationPopupPanel(directive())
        val host = TaigaDocumentationPopupHost(initial)
        host.showCard(expanded)
        assertEquals(expanded.preferredSize, host.preferredSize)
    }

    fun testHoverPlacementPrefersRightWithoutCoveringTheAttribute() {
        val target = java.awt.Rectangle(220, 160, 75, 20)
        val size = java.awt.Dimension(360, 240)
        val placement =
            calculateTaigaHoverPopupPlacement(target, size, java.awt.Rectangle(0, 0, 1200, 800), 12)
        assertEquals(TaigaHoverPointerSide.LEFT, placement.pointerSide)
        assertEquals(target.x + target.width + 12, placement.location.x)
        assertTrue(placement.location.y <= target.y + target.height)
    }

    fun testHoverPlacementFlipsLeftNearTheRightScreenEdge() {
        val target = java.awt.Rectangle(900, 380, 80, 22)
        val size = java.awt.Dimension(360, 240)
        val placement =
            calculateTaigaHoverPopupPlacement(target, size, java.awt.Rectangle(0, 0, 1200, 800), 12)
        assertEquals(TaigaHoverPointerSide.RIGHT, placement.pointerSide)
        assertTrue(placement.location.x + size.width < target.x)
    }

    fun testHoverPlacementFallsBelowOnNarrowScreens() {
        val target = java.awt.Rectangle(230, 40, 70, 20)
        val size = java.awt.Dimension(370, 220)
        val placement =
            calculateTaigaHoverPopupPlacement(target, size, java.awt.Rectangle(0, 0, 450, 700), 12)
        assertEquals(TaigaHoverPointerSide.NONE, placement.pointerSide)
        assertTrue(placement.location.y >= target.y + target.height)
    }

    fun testHoverPointerUsesTransparentGutterOutsideTheCard() {
        val owner = directive()
        val member = owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT)
        val panel = TaigaQuickDocumentationPopupPanel(member, hoverPointer = true)
        panel.alignHoverPointer(140)
        panel.size = panel.preferredSize
        panel.doLayout()
        val image = java.awt.image.BufferedImage(panel.width, panel.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            panel.paint(graphics)
        } finally {
            graphics.dispose()
        }
        assertEquals(0, image.getRGB(3, 3) ushr 24)
        assertEquals(panel.background.rgb, image.getRGB(16, 30))
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
}event)">...</a>""",
            documentationMemberUsage("a", selectors, "loaded", TaigaApiMemberKind.OUTPUT),
        )
        assertEquals(
            """<div [size]="value">...</div>""",
            documentationMemberUsage("div", selectors, "size", TaigaApiMemberKind.INPUT),
        )
    }

    fun testUsageKeepsTheDirectiveAttributeWithoutDuplicatingTheTagName() {
        assertEquals(
            """<button tuiButton [size]="value">...</button>""",
            documentationMemberUsage(
                "button",
                "a[tuiButton],button[tuiButton],label[tuiButton]",
                "size",
                TaigaApiMemberKind.INPUT,
            ),
        )
        assertEquals(
            """<button tuiButton [size]="value">...</button>""",
            documentationMemberUsage("button", "tuiButton", "size", TaigaApiMemberKind.INPUT),
        )
    }

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

    fun testHoverPointerDoesNotPaintAnEditorBackgroundStripeAcrossTheCard() {
        val owner = directive()
        val member = owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT)
        val panel = TaigaQuickDocumentationPopupPanel(member, hoverPointer = true)
        panel.alignHoverPointer(140)
        panel.size = panel.preferredSize
        panel.doLayout()

        val image = java.awt.image.BufferedImage(panel.width, panel.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            panel.paint(graphics)
        } finally {
            graphics.dispose()
        }

        // The top inset is part of the card, not a full-width stripe painted in the editor color.
        assertEquals(panel.background.rgb, image.getRGB(panel.width - 16, 3))
    }

    fun testHoverSwitchUsesShorterDwellWithoutMovingOnTinyGeometryChanges() {
        assertEquals(850.milliseconds, taigaDocumentationHoverDelay(switching = false))
        assertEquals(200.milliseconds, taigaDocumentationHoverDelay(switching = true))
        assertFalse(shouldRepositionTaigaHoverCard(java.awt.Point(100, 100), java.awt.Point(102, 98)))
        assertTrue(shouldRepositionTaigaHoverCard(java.awt.Point(100, 100), java.awt.Point(116, 100)))
    }

    fun testHoverCardHostKeepsItsContainerAndReplacesOnlyTheContent() {
        val first = TaigaQuickDocumentationPopupPanel(directive())
        val host = TaigaDocumentationPopupHost(first)
        val next = TaigaQuickDocumentationPopupPanel(component())
        assertSame(first, host.card)
        assertSame(host, first.parent)

        host.showCard(next)
        assertSame(next, host.card)
        assertSame(host, next.parent)
        assertNull(first.parent)
        assertEquals(1, host.componentCount)

        host.showCard(next)
        assertEquals(1, host.componentCount)
        host.stopTransition()
    }

    fun testHoverSwitchResizesForFullDirectiveAndRestoresCompactSize() {
        val small = TaigaQuickDocumentationPopupPanel(pipe(), compactHover = true, hoverPointer = true)
        val large = TaigaQuickDocumentationPopupPanel(directive(), hoverPointer = true)
        val host = TaigaDocumentationPopupHost(small, stabilizesHoverSize = true)
        val initialSize = java.awt.Dimension(host.preferredSize)

        assertTrue(initialSize.width >= JBUI.scale(380))
        assertTrue(initialSize.height in JBUI.scale(220)..JBUI.scale(300))
        host.showCard(large)
        assertEquals(large.preferredSize, host.preferredSize)
        assertEquals(1, host.componentCount)

        host.size = host.preferredSize
        host.doLayout()
        assertEquals(host.preferredSize, large.size)

        host.showCard(small)
        assertEquals(initialSize, host.preferredSize)
        host.stopTransition()
    }

    fun testDirectiveFirstHoverCanSwitchToCompactInputWithoutKeepingFullHeight() {
        val directive = TaigaQuickDocumentationPopupPanel(directive(), hoverPointer = true)
        val compact = TaigaQuickDocumentationPopupPanel(pipe(), compactHover = true, hoverPointer = true)
        val host = TaigaDocumentationPopupHost(directive, stabilizesHoverSize = true)

        assertEquals(directive.preferredSize, host.preferredSize)
        host.showCard(compact)
        assertTrue(host.preferredSize.height in JBUI.scale(220)..JBUI.scale(300))
        assertTrue(host.preferredSize.width >= JBUI.scale(380))
        assertSame(compact, host.card)
        host.stopTransition()
    }

    fun testComponentFullHoverRestoresCompactInputViewportWhenSwitching() {
        val compact = TaigaQuickDocumentationPopupPanel(pipe(), compactHover = true, hoverPointer = true)
        val component = TaigaQuickDocumentationPopupPanel(component(), hoverPointer = true)
        val host = TaigaDocumentationPopupHost(compact, stabilizesHoverSize = true)
        val compactSize = java.awt.Dimension(host.preferredSize)

        host.showCard(component)
        assertEquals(component.preferredSize, host.preferredSize)
        assertSame(component, host.card)

        host.showCard(compact)
        assertEquals(compactSize, host.preferredSize)
        host.stopTransition()
    }

    fun testShortHoverContentHidesScrollbarWithStableOuterSize() {
        val card = TaigaQuickDocumentationPopupPanel(pipe(), compactHover = true, hoverPointer = true)
        val host = TaigaDocumentationPopupHost(card, stabilizesHoverSize = true)
        val before = java.awt.Dimension(host.preferredSize)
        host.size = before
        host.doLayout()
        card.doLayout()
        val scroll = descendants(card).filterIsInstance<JBScrollPane>().single()
        scroll.doLayout()
        card.updateHoverScrollbarVisibility()

        assertEquals(
            JScrollPane.VERTICAL_SCROLLBAR_NEVER,
            taigaHoverScrollbarPolicy(scroll.viewport.extentSize.height, scroll.viewport.extentSize.height),
        )
        assertEquals(JScrollPane.VERTICAL_SCROLLBAR_NEVER, scroll.verticalScrollBarPolicy)
        assertEquals(before, host.preferredSize)
        host.stopTransition()
    }

    fun testLongHoverContentRemainsScrollableWithoutResizingHost() {
        val card = TaigaQuickDocumentationPopupPanel(directive(), compactHover = true, hoverPointer = true)
        val host = TaigaDocumentationPopupHost(card, stabilizesHoverSize = true)
        val before = java.awt.Dimension(host.preferredSize)
        host.size = before
        host.doLayout()
        card.doLayout()
        val scroll = descendants(card).filterIsInstance<JBScrollPane>().single()
        scroll.doLayout()
        assertEquals(
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            taigaHoverScrollbarPolicy(
                scroll.viewport.extentSize.height + JBUI.scale(80),
                scroll.viewport.extentSize.height,
            ),
        )
        assertEquals(before, host.preferredSize)
        host.stopTransition()
    }

    fun testFullDocumentationHostStillUsesNaturalContentSize() {
        val initial = TaigaQuickDocumentationPopupPanel(pipe(), compactHover = true)
        val expanded = TaigaQuickDocumentationPopupPanel(directive())
        val host = TaigaDocumentationPopupHost(initial)
        host.showCard(expanded)
        assertEquals(expanded.preferredSize, host.preferredSize)
    }

    fun testHoverPlacementPrefersRightWithoutCoveringTheAttribute() {
        val target = java.awt.Rectangle(220, 160, 75, 20)
        val size = java.awt.Dimension(360, 240)
        val placement =
            calculateTaigaHoverPopupPlacement(target, size, java.awt.Rectangle(0, 0, 1200, 800), 12)
        assertEquals(TaigaHoverPointerSide.LEFT, placement.pointerSide)
        assertEquals(target.x + target.width + 12, placement.location.x)
        assertTrue(placement.location.y <= target.y + target.height)
    }

    fun testHoverPlacementFlipsLeftNearTheRightScreenEdge() {
        val target = java.awt.Rectangle(900, 380, 80, 22)
        val size = java.awt.Dimension(360, 240)
        val placement =
            calculateTaigaHoverPopupPlacement(target, size, java.awt.Rectangle(0, 0, 1200, 800), 12)
        assertEquals(TaigaHoverPointerSide.RIGHT, placement.pointerSide)
        assertTrue(placement.location.x + size.width < target.x)
    }

    fun testHoverPlacementFallsBelowOnNarrowScreens() {
        val target = java.awt.Rectangle(230, 40, 70, 20)
        val size = java.awt.Dimension(370, 220)
        val placement =
            calculateTaigaHoverPopupPlacement(target, size, java.awt.Rectangle(0, 0, 450, 700), 12)
        assertEquals(TaigaHoverPointerSide.NONE, placement.pointerSide)
        assertTrue(placement.location.y >= target.y + target.height)
    }

    fun testHoverPointerUsesTransparentGutterOutsideTheCard() {
        val owner = directive()
        val member = owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT)
        val panel = TaigaQuickDocumentationPopupPanel(member, hoverPointer = true)
        panel.alignHoverPointer(140)
        panel.size = panel.preferredSize
        panel.doLayout()
        val image = java.awt.image.BufferedImage(panel.width, panel.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            panel.paint(graphics)
        } finally {
            graphics.dispose()
        }
        assertEquals(0, image.getRGB(3, 3) ushr 24)
        assertEquals(panel.background.rgb, image.getRGB(16, 30))
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
