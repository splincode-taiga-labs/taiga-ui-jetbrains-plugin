package org.taigaui.designtokens.settings

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Container
import javax.swing.JCheckBox

class TaigaDesignTokensConfigurableTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            service<TaigaDesignTokensSettings>().apply {
                showCompletionPreview = true
                showHoverPopup = true
            }
        } finally {
            super.tearDown()
        }
    }

    fun testPanelAppliesCheckboxChangesToSettings() {
        val settings = service<TaigaDesignTokensSettings>()

        settings.showCompletionPreview = true
        settings.showHoverPopup = true

        val panel = TaigaDesignTokensConfigurable().createPanel()
        val checkBoxes = panel.findCheckBoxes().associateBy(JCheckBox::getText)
        val completion =
            requireNotNull(checkBoxes["Show design token completion preview"])
        val hover =
            requireNotNull(checkBoxes["Show design token hover popup"])

        assertTrue(completion.isSelected)
        assertTrue(hover.isSelected)

        completion.isSelected = false
        hover.isSelected = false
        panel.apply()

        assertFalse(settings.showCompletionPreview)
        assertFalse(settings.showHoverPopup)

        settings.showCompletionPreview = true
        settings.showHoverPopup = false
        panel.reset()

        assertTrue(completion.isSelected)
        assertFalse(hover.isSelected)
    }

    private fun Container.findCheckBoxes(): List<JCheckBox> =
        components.flatMap { component ->
            when (component) {
                is JCheckBox -> listOf(component)
                is Container -> component.findCheckBoxes()
                else -> emptyList()
            }
        }
}
