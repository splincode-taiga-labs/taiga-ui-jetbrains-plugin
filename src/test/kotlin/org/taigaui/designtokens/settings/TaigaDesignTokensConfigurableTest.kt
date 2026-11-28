package org.taigaui.designtokens.settings

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.JCheckBox
import javax.swing.JRadioButton

class TaigaDesignTokensConfigurableTest : BasePlatformTestCase() {
    private val settings: TaigaDesignTokensSettings
        get() = service<TaigaDesignTokensSettings>()

    private lateinit var originalNetworkMode: TaigaNetworkMode

    override fun setUp() {
        super.setUp()
        originalNetworkMode = settings.networkMode
    }

    override fun tearDown() {
        try {
            settings.apply {
                networkMode = originalNetworkMode
                showCompletionPreview = true
                showHoverPopup = true
                showApiCardsOnHover = true
                exposeMcpTools = true
                allowMcpChanges = true
            }
        } finally {
            super.tearDown()
        }
    }

    fun testPanelAppliesCheckboxChangesToSettings() {
        settings.showCompletionPreview = true
        settings.showHoverPopup = true

        val panel = TaigaDesignTokensConfigurable().createPanel()
        val checkBoxes = panel.descendants().filterIsInstance<JCheckBox>().associateBy(JCheckBox::getText)
        val completion =
            requireNotNull(checkBoxes["Show completion preview"])
        val hover =
            requireNotNull(checkBoxes["Show hover popup"])

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

    fun testApiHoverAndMcpControlsApplyWithoutOverwritingTheWritePreference() {
        val panel = TaigaDesignTokensConfigurable().createPanel()
        val checks = panel.descendants().filterIsInstance<JCheckBox>().associateBy(JCheckBox::getText)
        val hover = requireNotNull(checks["Show API cards on hover"])
        val expose = requireNotNull(checks["Expose Taiga UI tools via MCP"])
        val changes = requireNotNull(checks["Allow project changes via MCP"])

        hover.isSelected = false
        expose.isSelected = false
        assertFalse(changes.isEnabled)
        panel.apply()

        assertFalse(settings.showApiCardsOnHover)
        assertFalse(settings.exposeMcpTools)
        assertTrue(settings.allowMcpChanges)

        expose.isSelected = true
        assertTrue(changes.isEnabled)
        changes.isSelected = false
        panel.apply()

        assertTrue(settings.exposeMcpTools)
        assertFalse(settings.allowMcpChanges)
    }

    fun testNetworkChoicesApplyAndReset() {
        settings.networkMode = TaigaNetworkMode.CACHE_FIRST
        val panel = TaigaDesignTokensConfigurable().createPanel()
        val choices = panel.descendants().filterIsInstance<JRadioButton>().associateBy(JRadioButton::getText)
        val cacheFirst = requireNotNull(choices["Cache first (default)"])
        val online = requireNotNull(choices["Online"])
        val offline = requireNotNull(choices["Offline only"])

        assertTrue(cacheFirst.isSelected)
        offline.doClick()
        assertEquals(TaigaNetworkMode.CACHE_FIRST, settings.networkMode)
        panel.apply()

        assertEquals(TaigaNetworkMode.OFFLINE_ONLY, settings.networkMode)
        assertFalse(cacheFirst.isSelected)
        assertTrue(offline.isSelected)

        settings.networkMode = TaigaNetworkMode.ONLINE
        panel.reset()

        assertTrue(online.isSelected)
        assertFalse(offline.isSelected)
    }

    private fun Container.descendants(): List<Component> =
        components.flatMap { component ->
            listOf(component) + if (component is Container) component.descendants() else emptyList()
        }
}
