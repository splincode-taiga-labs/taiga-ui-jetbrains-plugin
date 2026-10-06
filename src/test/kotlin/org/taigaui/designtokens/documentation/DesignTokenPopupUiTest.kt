package org.taigaui.designtokens.documentation

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import org.taigaui.designtokens.index.DesignTokenDeprecation
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.Timer

class DesignTokenPopupUiTest : BasePlatformTestCase() {
    fun testRendersLoadingAndCompleteModelAndHandlesActions() {
        val navigated = mutableListOf<DesignTokenNavigationTarget>()
        var reportBugClicks = 0
        val preferredSizes = mutableListOf<Dimension>()
        val panel =
            DesignTokenHoverPopupPanel(
                popupWidth = 560,
                onNavigate = navigated::add,
                onReportBug = { reportBugClicks++ },
                onPreferredSizeChanged = preferredSizes::add,
            )

        panel.showLoading("--tui-text-primary")

        assertTrue(
            panel
                .allComponents()
                .any { component ->
                    componentText(component) == "--tui-text-primary"
                },
        )
        assertTrue(
            panel
                .allComponents()
                .any { component ->
                    componentText(component) == "Loading design token graph…"
                },
        )
        assertTrue(preferredSizes.isNotEmpty())

        panel.showModel(fullModel())

        assertTrue(
            panel
                .allComponents()
                .any { component -> componentText(component) == "Deprecated" },
        )
        assertTrue(
            panel
                .allComponents()
                .any { component ->
                    componentText(component) == "Use --tui-text-secondary instead."
                },
        )
        assertTrue(
            panel
                .allComponents()
                .any { component ->
                    componentText(component) == "@taiga-ui/design-tokens"
                },
        )
        assertTrue(
            panel
                .allComponents()
                .any { component ->
                    componentText(component) == "@taiga-ui/proprietary"
                },
        )

        val toggle =
            panel
                .allComponents()
                .filterIsInstance<ActionLink>()
                .single { link -> link.text.startsWith("▸ Reference chain") }

        toggle.doClick()

        assertTrue(toggle.text.startsWith("▾ Reference chain"))
        assertTrue(
            panel
                .allComponents()
                .any { component -> componentText(component) == "--tui-base" },
        )

        toggle.doClick()
        assertTrue(toggle.text.startsWith("▸ Reference chain"))

        toggle.doClick()
        assertTrue(toggle.text.startsWith("▾ Reference chain"))

        val navigateButton =
            panel
                .allComponents()
                .filterIsInstance<NavigateToDefinitionButton>()
                .first()

        navigateButton.doClick()

        assertEquals(
            DesignTokenNavigationTarget(Path.of("tokens.css"), 7),
            navigated.single(),
        )

        val reportBug =
            panel
                .allComponents()
                .filterIsInstance<ActionLink>()
                .single { link -> link.text == "Report a bug" }

        reportBug.doClick()

        assertEquals(1, reportBugClicks)
        assertTrue(preferredSizes.size >= 2)
    }

    fun testCopyButtonShowsFeedbackAndResetsIt() {
        val button = CopyValueButton("rgba(1, 2, 3, 0.5)")

        button.doClick()

        assertEquals("Copied", button.toolTipText)

        val timerField =
            CopyValueButton::class.java
                .getDeclaredField("resetTimer")
                .apply { isAccessible = true }
        val timer = timerField.get(button) as Timer

        timer.actionListeners.forEach { listener ->
            listener.actionPerformed(ActionEvent(timer, ActionEvent.ACTION_PERFORMED, "reset"))
        }

        assertEquals("Copy value", button.toolTipText)
    }

    fun testPaintsPopupComponentsForOpaqueAndTransparentColors() {
        val components =
            listOf<JComponent>(
                TokenBadge(),
                ReferenceDot(),
                ColorSwatch(Color(10, 20, 30, 255), DESIGN_TOKEN_POPUP_SWATCH_SIZE),
                ColorSwatch(Color(10, 20, 30, 128), DESIGN_TOKEN_POPUP_SMALL_SWATCH_SIZE),
                RoundedRowPanel(),
            )

        components.forEach { component ->
            component.paintInto()
        }

        assertEquals("rgba(10, 20, 30, 1)", (components[2] as ColorSwatch).toolTipText)
        assertEquals("rgba(10, 20, 30, 0.5)", (components[3] as ColorSwatch).toolTipText)
        assertTrue(calculateNaturalTextWidth("var(--tui-text-primary)") > 0)
    }

    fun testRendersSmallAndLargePopupsWithoutReferenceChains() {
        val noReferences =
            DesignTokenHoverPopupModel(
                tokenName = "--tui-empty",
                description = "Nothing found",
                sections = emptyList(),
                deprecation = DesignTokenDeprecation(),
            )

        listOf(120, 2_000).forEach { width ->
            val panel =
                DesignTokenHoverPopupPanel(
                    popupWidth = width,
                    onNavigate = {},
                    onReportBug = {},
                    onPreferredSizeChanged = {},
                )

            panel.showModel(noReferences)

            assertFalse(
                panel
                    .allComponents()
                    .filterIsInstance<ActionLink>()
                    .any { link -> link.text.startsWith("▸ Reference chain") },
            )
        }
    }

    private fun fullModel(): DesignTokenHoverPopupModel =
        DesignTokenHoverPopupModel(
            tokenName = "--tui-text-primary",
            description = "Primary text color",
            deprecation = DesignTokenDeprecation(replacement = "--tui-text-secondary"),
            sections =
                listOf(
                    DesignTokenHoverPackageSection(
                        packageName = "@taiga-ui/design-tokens",
                        rows =
                            listOf(
                                DesignTokenHoverValueRow(
                                    platform = "🖥️ Desktop · Light ☀️",
                                    resolvedValue = "#ffffff",
                                    color = Color.WHITE,
                                    navigationTarget = DesignTokenNavigationTarget(Path.of("tokens.css"), 7),
                                ),
                                DesignTokenHoverValueRow(
                                    platform = "📱 Mobile · Dark 🌚",
                                    resolvedValue = "rgba(0, 0, 0, 0.5)",
                                    color = null,
                                    navigationTarget = null,
                                    overrideMessage = "Overridden by @taiga-ui/proprietary",
                                ),
                            ),
                        chains =
                            listOf(
                                DesignTokenHoverReferenceChain(
                                    platform = "🖥️ Desktop · Light ☀️",
                                    lines =
                                        listOf(
                                            DesignTokenHoverReferenceLine(
                                                text = "--tui-text-primary",
                                                depth = 0,
                                                root = true,
                                                color = Color.WHITE,
                                            ),
                                            DesignTokenHoverReferenceLine(
                                                text = "--tui-base",
                                                depth = 1,
                                                color = null,
                                            ),
                                        ),
                                ),
                                DesignTokenHoverReferenceChain(
                                    platform = "📱 Mobile · Dark 🌚",
                                    lines =
                                        listOf(
                                            DesignTokenHoverReferenceLine(
                                                text = "--tui-mobile",
                                                depth = 0,
                                                root = true,
                                                color = null,
                                            ),
                                        ),
                                ),
                            ),
                    ),
                    DesignTokenHoverPackageSection(
                        packageName = "@taiga-ui/proprietary",
                        rows =
                            listOf(
                                DesignTokenHoverValueRow(
                                    platform = "🖥️ Desktop · Light ☀️",
                                    resolvedValue = "black",
                                    color = Color.BLACK,
                                    navigationTarget = null,
                                ),
                            ),
                        chains =
                            listOf(
                                DesignTokenHoverReferenceChain(
                                    platform = "🖥️ Desktop · Light ☀️",
                                    overrideMessage = "Not selected",
                                    lines =
                                        listOf(
                                            DesignTokenHoverReferenceLine(
                                                text = "--tui-proprietary",
                                                depth = 0,
                                                root = true,
                                                color = Color.BLACK,
                                            ),
                                            DesignTokenHoverReferenceLine(
                                                text = "#000",
                                                depth = 2,
                                                root = false,
                                                color = null,
                                            ),
                                        ),
                                ),
                            ),
                    ),
                ),
        )

    private fun JComponent.paintInto() {
        val width = preferredSize.width.coerceAtLeast(64)
        val height = preferredSize.height.coerceAtLeast(32)

        setSize(width, height)

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()

        try {
            paint(graphics)
        } finally {
            graphics.dispose()
        }
    }

    private fun Container.allComponents(): Sequence<Component> =
        components.asSequence().flatMap { component ->
            sequenceOf(component) +
                if (component is Container) {
                    component.allComponents()
                } else {
                    emptySequence()
                }
        }

    private fun componentText(component: Component): String? =
        when (component) {
            is AbstractButton -> component.text
            is javax.swing.JLabel -> component.text
            is javax.swing.text.JTextComponent -> component.text
            else -> null
        }
}
