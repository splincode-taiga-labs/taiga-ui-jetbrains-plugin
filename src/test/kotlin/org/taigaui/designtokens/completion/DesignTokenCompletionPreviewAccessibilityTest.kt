package org.taigaui.designtokens.completion

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import org.taigaui.designtokens.documentation.DesignTokenHoverPackageSection
import org.taigaui.designtokens.documentation.DesignTokenHoverPopupModel
import org.taigaui.designtokens.documentation.DesignTokenHoverValueRow
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import javax.swing.JTextArea

class DesignTokenCompletionPreviewAccessibilityTest : BasePlatformTestCase() {
    fun testPreviewIsPassiveAndExposesSelectedTokenToAccessibility() {
        val panel = DesignTokenCompletionPreviewPanel()

        assertFalse(panel.isFocusable)
        panel.accessibleContext?.let { context ->
            assertEquals("Taiga UI design token completion preview", context.accessibleName)
        }

        panel.showLoading("--tui-text-primary")
        panel.accessibleContext?.let { context ->
            assertEquals(
                "Loading resolved values for --tui-text-primary.",
                context.accessibleDescription,
            )
        }

        panel.showModel(model())

        panel.accessibleContext?.let { context ->
            assertEquals(
                "Resolved values for --tui-text-primary.",
                context.accessibleDescription,
            )
        }
    }

    fun testColorPreviewHasTextualAccessibleDescription() {
        val panel = DesignTokenCompletionPreviewPanel()

        panel.showModel(model())

        val colorPreview =
            panel
                .descendants()
                .filterIsInstance<JBLabel>()
                .single { label -> label.accessibleContext.accessibleName == "Color preview" }

        colorPreview.accessibleContext?.let { context ->
            assertEquals("Color preview", context.accessibleName)
            assertEquals("Color preview for #ff0000.", context.accessibleDescription)
        }
        assertTrue(
            panel
                .descendants()
                .any { component ->
                    component is JTextArea && component.text == "#ff0000"
                },
        )
    }

    fun testColorPreviewPaintsOpaqueColorIntoBufferedImage() {
        val panel = DesignTokenCompletionPreviewPanel()

        panel.showModel(model())

        val iconLabel =
            panel
                .descendants()
                .filterIsInstance<JBLabel>()
                .single { label -> label.icon != null }
        val icon = requireNotNull(iconLabel.icon)
        val image =
            BufferedImage(
                icon.iconWidth + 4,
                icon.iconHeight + 4,
                BufferedImage.TYPE_INT_ARGB,
            )
        val graphics = image.createGraphics()

        try {
            icon.paintIcon(iconLabel, graphics, 2, 2)
        } finally {
            graphics.dispose()
        }

        assertEquals(
            Color.RED.rgb,
            image.getRGB(
                2 + icon.iconWidth / 2,
                2 + icon.iconHeight / 2,
            ),
        )
    }

    private fun model(): DesignTokenHoverPopupModel =
        DesignTokenHoverPopupModel(
            tokenName = "--tui-text-primary",
            description = null,
            sections =
                listOf(
                    DesignTokenHoverPackageSection(
                        packageName = "@taiga-ui/design-tokens",
                        rows =
                            listOf(
                                DesignTokenHoverValueRow(
                                    platform = "Desktop",
                                    resolvedValue = "#ff0000",
                                    color = Color.RED,
                                    navigationTarget = null,
                                ),
                            ),
                        chains = emptyList(),
                    ),
                ),
        )
}

private fun Container.descendants(): Sequence<Component> =
    components.asSequence().flatMap { component ->
        sequenceOf(component) +
            if (component is Container) {
                component.descendants()
            } else {
                emptySequence()
            }
    }
