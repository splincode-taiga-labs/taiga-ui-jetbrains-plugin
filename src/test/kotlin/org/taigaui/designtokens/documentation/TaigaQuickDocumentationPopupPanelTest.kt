package org.taigaui.designtokens.documentation

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.labels.LinkLabel
import org.taigaui.designtokens.icons.IconSvgPreviewRenderer
import org.taigaui.designtokens.icons.IconSvgSource
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JTabbedPane
import javax.swing.JViewport
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

class TaigaQuickDocumentationPopupPanelTest : BasePlatformTestCase() {
    fun testDirectiveCardShowsParametersImmediatelyWithoutDuplicateMarkup() {
        val declaration =
            TaigaDocumentationSubject(
                null,
                "TuiSizeDirective",
                "@taiga-ui/core",
                TaigaLocalDocumentation(inputTypes = mapOf("size" to "'s' | 'm'")),
            )
        val binding = TaigaDocumentationBinding("size", 0, 8, "size=\"s\"", 6, 7, "s", false, declaration = declaration)
        val resolved = directive().copy(bindings = listOf(binding))
        var selected: String? = null
        var selectedMember: TaigaResolvedDocumentation.Member? = null
        val panel =
            TaigaQuickDocumentationPopupPanel(
                resolved,
                actions =
                    TaigaDocumentationPopupActions(
                        openMember = {
                            selected = it.property.name
                            selectedMember = it
                        },
                    ),
            )
        val components = descendants(panel).toList()
        val text = labelText(panel)

        assertFalse(components.any { it is JTabbedPane })
        assertTrue(text.contains("Parameters"))
        assertTrue(text.contains("TUI_BUTTON_OPTIONS"))
        assertFalse(text.contains("automation-id"))
        assertFalse(text.contains("import {"))
        val sizeLink = components.filterIsInstance<LinkLabel<*>>().first { it.text == "size" }
        sizeLink.doClick()
        assertEquals("size", selected)
        assertEquals(listOf("s", "m"), selectedMember?.localValues())
    }

    fun testPipeDoesNotInventPurityFromStandaloneMetadata() {
        val local =
            TaigaLocalDocumentationParser.parse(
                """class TuiMapperPipe { transform(value: unknown): string; static ɵpipe: i0.ɵɵPipeDeclaration<TuiMapperPipe, "tuiMapper", true>; }""",
            )
        val panel = TaigaQuickDocumentationPopupPanel(pipe(local))

        assertTrue(labelText(panel).contains("Parameters"))
        assertFalse(labelText(panel).contains("Pure pipe"))
    }

    fun testIconChooserUsesExactStaticReference() {
        val reference = TaigaDocumentationIcon("icon", "@tui.eye", 4, 12)
        var chosen: TaigaDocumentationIcon? = null
        val entity = component().copy(icons = listOf(reference))
        val panel =
            TaigaQuickDocumentationPopupPanel(
                entity,
                actions = TaigaDocumentationPopupActions(chooseIcon = { chosen = it }),
            )

        descendants(panel).filterIsInstance<JButton>().first { it.text == "Choose icon" }.doClick()
        assertEquals(reference, chosen)
    }

    fun testFocusedIconInputHasItsOwnPreviewChooserAndOwnerApi() {
        val start = TaigaDocumentationIcon("iconStart", "@tui.chevron", 3, 15)
        val end = TaigaDocumentationIcon("iconEnd", "@tui.eye", 30, 38)
        val owner = directive().copy(icons = listOf(start, end))
        val member = owner.focusedMember(owner.entity.inputs.first { it.name == "iconEnd" }, TaigaApiMemberKind.INPUT)
        var chosen: TaigaDocumentationIcon? = null
        var opened: TaigaResolvedDocumentation.Entity? = null
        val panel =
            TaigaQuickDocumentationPopupPanel(
                member,
                actions = TaigaDocumentationPopupActions(chooseIcon = { chosen = it }, openOwner = { opened = it }),
                previews = listOf(iconPreview(end)),
            )
        assertEquals(listOf(end), member.documentationIcons)
        descendants(panel).filterIsInstance<JButton>().first { it.text == "Choose icon" }.doClick()
        assertEquals(end, chosen)
        descendants(panel).filterIsInstance<LinkLabel<*>>().first { it.text == "View TuiButton API →" }.doClick()
        assertEquals(owner.entity.inputs, opened?.entity?.inputs)
        assertTrue(labelText(panel).contains("@tui.eye"))
        assertFalse(labelText(panel).contains("@tui.chevron"))
        renderAndSave(panel, "icon-input")
    }

    fun testFullApiSearchKeyboardSelectionAndBackWorkWithoutPsi() {
        val owner = directive()
        val entity =
            owner.copy(
                entity =
                    owner.entity.copy(
                        outputs = listOf(TaigaApiProperty("valueChange", "(valueChange)", "string", "Value event")),
                    ),
            )
        var member: TaigaResolvedDocumentation.Member? = null
        var query = ""
        var back = false
        val panel =
            TaigaQuickDocumentationPopupPanel(
                entity,
                actions =
                    TaigaDocumentationPopupActions(
                        openMember = { member = it },
                        queryChanged = { query = it },
                        goBack = { back = true },
                    ),
                apiQuery = "visual",
            )
        val search = descendants(panel).filterIsInstance<JBTextField>().single()
        val results = descendants(panel).filterIsInstance<JBList<*>>().single()
        val browser = descendants(panel).filterIsInstance<TaigaDocumentationApiBrowser>().single()
        assertTrue(browser.alignmentX == JComponent.LEFT_ALIGNMENT)
        assertSame(search, panel.preferredFocus)
        assertEquals(1, results.model.size)
        assertEquals("visual", query)
        search.postActionEvent()
        assertEquals("appearance", member?.property?.name)
        search.text = "icon"
        assertEquals(2, results.model.size)
        results.selectedIndex = 1
        val enter =
            results
                .getInputMap(
                    JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
                ).get(KeyStroke.getKeyStroke("ENTER"))
        results.actionMap.get(enter).actionPerformed(ActionEvent(results, ActionEvent.ACTION_PERFORMED, "open"))
        assertEquals("iconEnd", member?.property?.name)
        assertEquals(TaigaApiMemberKind.INPUT, member?.kind)
        search.text = "VALUE EVENT"
        assertEquals(1, results.model.size)
        results.actionMap.get(enter).actionPerformed(ActionEvent(results, ActionEvent.ACTION_PERFORMED, "open"))
        assertEquals("valueChange", member?.property?.name)
        assertEquals(TaigaApiMemberKind.OUTPUT, member?.kind)
        search.text = "does-not-exist"
        assertEquals(0, results.model.size)
        search.text = ""
        val backKey =
            panel
                .getInputMap(
                    JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
                ).get(KeyStroke.getKeyStroke("alt LEFT"))
        panel.actionMap.get(backKey).actionPerformed(ActionEvent(panel, ActionEvent.ACTION_PERFORMED, "back"))
        assertTrue(back)
        renderAndSave(panel, "api-browser")
        val provider =
            descendants(panel)
                .filterIsInstance<JBLabel>()
                .first { it.text.orEmpty().contains("Project providers") }
        assertTrue("Provider explanation must fit the API card", provider.width >= provider.preferredSize.width)
        val focused =
            TaigaQuickDocumentationPopupPanel(
                requireNotNull(member),
                actions = TaigaDocumentationPopupActions(goBack = { back = true }),
            )
        assertEquals("Back", (focused.preferredFocus as? JButton)?.text)
    }

    fun testEmptyIconInputOffersChooseIconWithoutAnSvgPreview() {
        val reference = TaigaDocumentationIcon("iconEnd", "", 20, 20)
        val owner = directive().copy(icons = listOf(reference))
        val member = owner.focusedMember(owner.entity.inputs.first { it.name == "iconEnd" }, TaigaApiMemberKind.INPUT)
        var chosen: TaigaDocumentationIcon? = null
        val panel =
            TaigaQuickDocumentationPopupPanel(
                member,
                actions =
                    TaigaDocumentationPopupActions(chooseIcon = {
                        chosen =
                            it
                    }),
            )
        descendants(panel).filterIsInstance<JButton>().first { it.text == "Choose icon" }.doClick()
        assertEquals(reference, chosen)
        assertFalse(labelText(panel).contains("Icon from current value"))
    }

    fun testPinAndCloseActionsAreExplicit() {
        var toggled = false
        var closed = false
        val panel =
            TaigaQuickDocumentationPopupPanel(
                directive(),
                onClose = { closed = true },
                actions = TaigaDocumentationPopupActions(togglePin = { toggled = true }),
                pinned = true,
            )
        descendants(panel).filterIsInstance<JButton>().first { it.text == "Unpin" }.doClick()
        descendants(panel).filterIsInstance<JButton>().first { it.text == "Close" }.doClick()
        assertTrue(toggled)
        assertTrue(closed)
    }

    fun testValueButtonAppliesAndUpdatesCurrentValue() {
        val entity = directive()
        val member =
            TaigaResolvedDocumentation.Member(
                entity.entity,
                entity.subject.copy(
                    localDocumentation = TaigaLocalDocumentation(inputTypes = mapOf("size" to "'s' | 'm'")),
                ),
                0,
                4,
                null,
                entity.entity.inputs.first(),
                TaigaApiMemberKind.INPUT,
                binding = TaigaDocumentationBinding("size", 0, 8, "size=\"s\"", 6, 7, "s", false),
            )
        var applied: String? = null
        val panel =
            TaigaQuickDocumentationPopupPanel(
                member,
                actions =
                    TaigaDocumentationPopupActions(
                        togglePin = {},
                        applyValue = {
                            applied = it
                            "Applied $it · Undo available"
                        },
                    ),
                pinned = true,
            )
        descendants(panel).filterIsInstance<JButton>().first { it.text == "Apply 'm'" }.doClick()
        assertEquals("m", applied)
        assertTrue(labelText(panel).contains("Current value: m"))
        assertTrue(labelText(panel).contains("Undo available"))
        renderAndSave(panel, "binding")
        val changingLabels =
            descendants(panel)
                .filterIsInstance<JBLabel>()
                .filter { it.text == "Current value: m" || it.text.startsWith("Applied m") }
        changingLabels.forEach {
            assertTrue("Feedback must fit: ${it.text}", it.width >= it.getFontMetrics(it.font).stringWidth(it.text))
        }
    }

    fun testDynamicAndDocumentationOnlyValuesAreCopyActions() {
        val entity = directive()
        val member =
            TaigaResolvedDocumentation.Member(
                entity.entity,
                entity.subject.copy(
                    localDocumentation = TaigaLocalDocumentation(inputTypes = mapOf("size" to "TuiSize")),
                ),
                0,
                4,
                null,
                TaigaApiProperty("size", "[size]", "'s' | 'm'", "Button size"),
                TaigaApiMemberKind.INPUT,
            )
        val panel = TaigaQuickDocumentationPopupPanel(member)
        val apply = descendants(panel).filterIsInstance<JButton>().first { it.text == "Apply 'm'" }
        assertFalse(apply.isEnabled)
        descendants(panel).filterIsInstance<JButton>().first { it.text == "Copy 'm'" }.doClick()
        assertTrue(labelText(panel).contains("Copied 'm'"))
        assertTrue(member.localValues().isEmpty())
    }

    fun testKeyboardReachesPinSourceApiAndCloseActions() {
        var pinned = false
        var source = false
        var api = 0
        var closed = false
        val panel =
            TaigaQuickDocumentationPopupPanel(
                directive(),
                onClose = { closed = true },
                actions =
                    TaigaDocumentationPopupActions(
                        togglePin = { pinned = true },
                        navigateToSource = { source = true },
                        openOwner = { api++ },
                    ),
            )
        assertTrue(panel.isFocusCycleRoot)
        assertEquals("Pin", (panel.preferredFocus as? JButton)?.text)
        activate(panel.preferredFocus, "ENTER")
        assertTrue(pinned)
        val links = descendants(panel).filterIsInstance<LinkLabel<*>>()
        val sourceLink = links.first { it.text == "Source ↗" }
        assertTrue(sourceLink.isFocusable)
        activate(sourceLink, "ENTER")
        activate(links.first { it.text == "Browse API →" }, "SPACE")
        assertTrue(source)
        assertEquals("Space must open the API exactly once", 1, api)
        activate(panel, "ESCAPE")
        assertTrue(closed)
    }

    fun testStaleCardBlocksIconWritesAndKeepsCopyAndKeyboardRefresh() {
        val entity = directive()
        val member =
            TaigaResolvedDocumentation.Member(
                entity.entity,
                entity.subject.copy(
                    localDocumentation = TaigaLocalDocumentation(inputTypes = mapOf("size" to "'s' | 'm'")),
                ),
                0,
                4,
                null,
                entity.entity.inputs.first(),
                TaigaApiMemberKind.INPUT,
                binding = TaigaDocumentationBinding("size", 0, 8, "size=\"s\"", 6, 7, "s", false),
                icons = listOf(TaigaDocumentationIcon("size", "@tui.eye", 0, 8)),
            )
        var writes = 0
        var refreshed = false
        val panel =
            TaigaQuickDocumentationPopupPanel(
                member,
                actions =
                    TaigaDocumentationPopupActions(
                        chooseIcon = { writes++ },
                        applyValue = {
                            writes++
                            "Applied $it"
                        },
                        refresh = { refreshed = true },
                    ),
            )
        panel.invalidateContext()
        val buttons = descendants(panel).filterIsInstance<JButton>()
        val chooser = buttons.first { it.text == "Choose icon" }
        assertFalse(chooser.isEnabled)
        activate(chooser, "ENTER")
        val apply = buttons.first { it.text == "Apply 'm'" }
        assertFalse(apply.isEnabled)
        activate(apply, "ENTER")
        activate(buttons.first { it.text == "Copy 'm'" }, "ENTER")
        assertEquals(0, writes)
        assertTrue(labelText(panel).contains("Copied 'm'"))
        assertTrue(labelText(panel).contains("Code changed"))
        activate(panel, "F5")
        assertTrue(refreshed)
        renderAndSave(panel, "stale-binding")
        val value = buttons.first { it.text == "Copy 'm'" }
        val viewport = descendants(panel).filterIsInstance<JViewport>().first()
        val position = SwingUtilities.convertPoint(value.parent, value.location, viewport)
        assertTrue(
            "Literal buttons must remain visible after invalidation",
            position.y + value.height <= viewport.height,
        )
    }

    fun testCardKeyboardActionDoesNotRequireMouseHoverSetting() {
        myFixture.configureByText("component.html", "<button tuiButton>Save</button>")
        val action = TaigaShowDocumentationCardAction()
        val context =
            DataContext { id ->
                when (id) {
                    CommonDataKeys.PROJECT.name -> project
                    CommonDataKeys.EDITOR.name -> myFixture.editor
                    CommonDataKeys.PSI_FILE.name -> myFixture.file
                    else -> null
                }
            }
        val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, context)
        val settings = EditorSettingsExternalizable.getInstance()
        val previous = settings.isShowQuickDocOnMouseOverElement
        try {
            settings.isShowQuickDocOnMouseOverElement = false
            action.update(event)
            assertTrue(event.presentation.isEnabled)
        } finally {
            settings.isShowQuickDocOnMouseOverElement = previous
        }
    }

    fun testAllValuesAreSearchableAndStaleFilteringCannotEnableWrites() {
        val owner = directive()
        val values = (1..15).map { "value$it" }
        val type = values.joinToString(" | ") { "'$it'" }
        val member =
            owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT).copy(
                subject =
                    owner.subject.copy(localDocumentation = TaigaLocalDocumentation(inputTypes = mapOf("size" to type))),
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
        assertFalse(descendants(panel).filterIsInstance<JButton>().any { it.text == "Apply 'value15'" })
        val more = descendants(panel).filterIsInstance<JButton>().first { it.text == "Show all (15)" }
        activate(more, "ENTER")
        val search = descendants(panel).filterIsInstance<JBTextField>().single()
        assertTrue(search.isVisible)
        search.text = "value15"
        activate(descendants(panel).filterIsInstance<JButton>().first { it.text == "Apply 'value15'" }, "ENTER")
        assertEquals("value15", applied)
        panel.invalidateContext()
        search.text = "value14"
        val apply = descendants(panel).filterIsInstance<JButton>().first { it.text == "Apply 'value14'" }
        assertFalse(apply.isEnabled)
        activate(apply, "ENTER")
        activate(descendants(panel).filterIsInstance<JButton>().first { it.text == "Copy 'value14'" }, "ENTER")
        assertEquals("value15", applied)
        assertEquals("'value14'", CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor))
        search.text = "unknown"
        assertTrue(labelText(panel).contains("0 of 15 values"))
    }

    fun testCopyActionsKeepCompleteTypesImportsAndExamples() {
        val owner = directive()
        val code = "<button tuiButton>Save</button>\n".repeat(60)
        val entity = owner.copy(entity = owner.entity.copy(example = TaigaExample("html", code)))
        val panel = TaigaQuickDocumentationPopupPanel(entity, showExample = true)
        activate(descendants(panel).filterIsInstance<JButton>().first { it.text == "Copy example" }, "ENTER")
        assertEquals(code, CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor))
        activate(descendants(panel).filterIsInstance<JButton>().first { it.text == "Copy import" }, "ENTER")
        assertEquals(
            "import {TuiButton} from '@taiga-ui/core';",
            CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor),
        )
        val member = owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT)
        val inputPanel = TaigaQuickDocumentationPopupPanel(member)
        activate(descendants(inputPanel).filterIsInstance<JButton>().first { it.text == "Copy type" }, "ENTER")
        assertEquals("TuiSize", CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor))
    }

    fun testRefreshProgressBlocksWritesAndKeepsCopyAndImportHelp() {
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
        descendants(panel).filterIsInstance<LinkLabel<*>>().first { it.text == "Angular quick fixes →" }.doClick()
        assertTrue(quickFixes)
        activate(descendants(panel).filterIsInstance<JButton>().first { it.text == "Copy import" }, "ENTER")
        assertTrue(CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor).toString().contains("TuiButton"))
        panel.showRefreshFailure()
        descendants(panel).filterIsInstance<JButton>().first { it.text == "Refresh" }.doClick()
        assertTrue(refreshed)
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
        val panel =
            TaigaQuickDocumentationPopupPanel(
                owner.copy(entity = owner.entity.copy(description = description)),
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

    fun testLongSharedBindingCardsFitLightAndDarkThemes() {
        val owner = directive()
        val type = "VeryLongInstalledInputType | ".repeat(30) + "string"
        val first =
            owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT).copy(
                property = owner.entity.inputs.first().copy(documentedType = type),
            )
        val second = first.copy(subject = first.subject.copy(publicSymbol = "TuiOtherReceiver"))
        val shared = first.copy(receivers = listOf(first, second))
        val previousBright = JBColor.isBright()
        try {
            listOf(false, true).forEach { dark ->
                JBColor.setDark(dark)
                val panel =
                    TaigaQuickDocumentationPopupPanel(
                        shared,
                        actions = TaigaDocumentationPopupActions(showImportFixes = {}),
                    )
                assertTrue("Long types must fit the card", panel.preferredSize.width <= 580)
                val copies =
                    descendants(panel).filterIsInstance<JButton>().filter { it.text.startsWith("Copy ") }.toList()
                assertEquals(2, copies.size)
                copies.forEach { assertEquals(type, it.toolTipText) }
                renderAndSave(panel, if (dark) "long-receivers-dark" else "long-receivers-light")
            }
        } finally {
            JBColor.setDark(!previousBright)
        }
    }

    fun testKeyboardCopiesStaticLiteralWithoutApplyingIt() {
        val entity = directive()
        val member =
            TaigaResolvedDocumentation.Member(
                entity.entity,
                entity.subject.copy(
                    localDocumentation = TaigaLocalDocumentation(inputTypes = mapOf("size" to "'s' | 'm'")),
                ),
                0,
                4,
                null,
                entity.entity.inputs.first(),
                TaigaApiMemberKind.INPUT,
                binding = TaigaDocumentationBinding("size", 0, 8, "size=\"s\"", 6, 7, "s", false),
            )
        var writes = 0
        val panel =
            TaigaQuickDocumentationPopupPanel(
                member,
                actions =
                    TaigaDocumentationPopupActions(applyValue = {
                        writes++
                        "Applied $it"
                    }),
            )
        val value = descendants(panel).filterIsInstance<JButton>().first { it.text == "Copy 'm'" }
        activate(value, "ENTER")
        value.doClick()
        assertEquals(0, writes)
        assertTrue(labelText(panel).contains("Copied 'm'"))
        val apply = descendants(panel).filterIsInstance<JButton>().first { it.text == "Apply 'm'" }
        activate(apply, "ENTER")
        assertEquals(1, writes)
        assertTrue(labelText(panel).contains("Applied m"))
    }

    private fun activate(
        component: JComponent,
        key: String,
    ) {
        val name = component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(KeyStroke.getKeyStroke(key))
        if (component is LinkLabel<*> && key == "SPACE") {
            name?.let { component.actionMap.get(it).actionPerformed(ActionEvent(component, 0, it.toString())) }
            val event = KeyEvent(component, KeyEvent.KEY_RELEASED, 0, 0, KeyEvent.VK_SPACE, ' ')
            component.keyListeners.forEach { it.keyReleased(event) }
            return
        }
        assertNotNull("Missing keyboard action: $key", name)
        component.actionMap
            .get(
                name,
            ).actionPerformed(ActionEvent(component, ActionEvent.ACTION_PERFORMED, name.toString()))
    }

    fun testRenderNativeCardsForVisualReview() {
        val previousBright = JBColor.isBright()
        JBColor.setDark(true)
        try {
            val cards = mapOf("component" to component(), "directive" to directive(), "pipe" to pipe())
            cards.forEach { (name, resolved) ->
                val reference = TaigaDocumentationIcon("icon", "@tui.eye", 4, 12)
                val documentation = if (name == "component") resolved.copy(icons = listOf(reference)) else resolved
                val previews = if (name == "component") listOf(iconPreview(reference)) else emptyList()
                val panel =
                    TaigaQuickDocumentationPopupPanel(
                        documentation,
                        actions =
                            TaigaDocumentationPopupActions(
                                navigateToSource = {},
                                chooseIcon = {},
                                openMember = {},
                                togglePin = {},
                            ),
                        previews = previews,
                    )
                renderAndSave(panel, name)
            }
        } finally {
            JBColor.setDark(!previousBright)
        }
    }

    private fun renderAndSave(
        panel: TaigaQuickDocumentationPopupPanel,
        name: String,
    ) {
        val size = panel.preferredSize
        assertTrue("Card must stay compact", size.height <= 700)
        panel.size = Dimension(size)
        layoutRecursively(panel)
        val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        panel.printAll(graphics)
        graphics.dispose()
        val path = Path.of("build/reports/quick-documentation/$name.png")
        Files.createDirectories(path.parent)
        ImageIO.write(image, "png", path.toFile())
    }

    private fun iconPreview(reference: TaigaDocumentationIcon): TaigaDocumentationIconPreview {
        val path = Files.createTempFile("quick-documentation-eye", ".svg")
        try {
            Files.writeString(
                path,
                """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path d="M2 12 Q12 -2 22 12 Q12 26 2 12Z" fill="none" stroke="#111" stroke-width="2"/><circle cx="12" cy="12" r="3" fill="none" stroke="#111" stroke-width="2"/></svg>""",
            )
            val icon =
                CompletableFuture
                    .supplyAsync {
                        IconSvgPreviewRenderer().render(
                            IconSvgSource.Local(path),
                            64,
                        )
                    }.join()
            return TaigaDocumentationIconPreview(reference, requireNotNull(icon))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun directive(): TaigaResolvedDocumentation.Entity =
        entity(
            name = "TuiButton",
            selector = "tuiButton",
            kind = TaigaDocKind.DIRECTIVE,
            description = "Styles native buttons. Size and appearance can be configured.",
            inputs =
                listOf(
                    TaigaApiProperty("size", "[size]", "TuiSize", "Button size"),
                    TaigaApiProperty("appearance", "[appearance]", "string", "Visual appearance"),
                    TaigaApiProperty("iconStart", "[iconStart]", "string", "Icon at the start"),
                    TaigaApiProperty("iconEnd", "[iconEnd]", "string", "Icon at the end"),
                ),
            local =
                TaigaLocalDocumentation(
                    selector = "a[tuiButton],button[tuiButton],label[tuiButton]",
                    defaults = listOf(TaigaInputDefault("size", provider = "TUI_BUTTON_OPTIONS")),
                ),
        )

    private fun component(): TaigaResolvedDocumentation.Entity =
        entity(
            name = "TuiIcon",
            selector = "tui-icon",
            kind = TaigaDocKind.COMPONENT,
            description = "Displays an icon with an optional badge and background.",
            inputs =
                listOf(
                    TaigaApiProperty("icon", "[icon]", "string", "Main icon"),
                    TaigaApiProperty("badge", "[badge]", "string", "Additional icon"),
                    TaigaApiProperty("background", "[background]", "string", "Background icon"),
                ),
        )

    private fun pipe(
        local: TaigaLocalDocumentation =
            TaigaLocalDocumentationParser.parse(
                """
                @Pipe({name: 'tuiMapper'})
                class TuiMapperPipe {
                    transform(value: U, mapper: TuiMapper<U, G>, ...args: unknown[]): G {
                        return mapper(value, ...args);
                    }
                }
                """.trimIndent(),
            ),
    ): TaigaResolvedDocumentation.Entity =
        entity(
            name = "TuiMapperPipe",
            selector = "tuiMapper",
            kind = TaigaDocKind.PIPE,
            description = "Passes a value to a mapper function. Extra arguments are forwarded to that function.",
            local = local,
        )

    private fun entity(
        name: String,
        selector: String,
        kind: TaigaDocKind,
        description: String,
        inputs: List<TaigaApiProperty> = emptyList(),
        local: TaigaLocalDocumentation = TaigaLocalDocumentation(),
    ): TaigaResolvedDocumentation.Entity {
        val packageName = if (kind == TaigaDocKind.PIPE) "@taiga-ui/cdk" else "@taiga-ui/core"
        return TaigaResolvedDocumentation.Entity(
            entity =
                TaigaEntityDoc(
                    "${kind.name.lowercase()}/$selector",
                    name,
                    setOf(packageName),
                    kind,
                    "5.0.0",
                    description,
                    setOf(name),
                    setOf(selector),
                    inputs,
                    emptyList(),
                    null,
                    java.net.URI.create("https://taiga-ui.dev/"),
                ),
            subject = TaigaDocumentationSubject(selector, name, packageName, local),
            startOffset = 0,
            endOffset = 9,
            usage = "<button tuiButton automation-id=\"already-visible\">Current</button>",
            typeDefinition = null,
        )
    }
}

private fun descendants(component: Component): Sequence<Component> =
    sequence {
        yield(component)
        if (component is Container) {
            component.components.forEach { child -> yieldAll(descendants(child)) }
        }
    }

private fun labelText(panel: Component): String =
    descendants(panel).filterIsInstance<JBLabel>().joinToString(" ") { it.text.orEmpty() }

private fun layoutRecursively(component: Component) {
    if (component is Container) {
        component.doLayout()
        component.components.forEach(::layoutRecursively)
    }
}
