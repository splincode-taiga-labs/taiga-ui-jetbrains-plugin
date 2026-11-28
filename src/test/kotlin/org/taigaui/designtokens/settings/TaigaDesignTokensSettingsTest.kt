package org.taigaui.designtokens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaigaDesignTokensSettingsTest {
    @Test
    fun `enables editor and MCP features by default`() {
        val settings = TaigaDesignTokensSettings()

        assertTrue(settings.showCompletionPreview)
        assertTrue(settings.showHoverPopup)
        assertTrue(settings.showApiCardsOnHover)
        assertTrue(settings.exposeMcpTools)
        assertTrue(settings.allowMcpChanges)
    }

    @Test
    fun `loads persisted editor and MCP settings`() {
        val settings = TaigaDesignTokensSettings()
        val state =
            TaigaDesignTokensSettings.SettingsState().apply {
                showCompletionPreview = false
                showHoverPopup = false
                showApiCardsOnHover = false
                exposeMcpTools = false
                allowMcpChanges = false
            }

        settings.loadState(state)

        assertFalse(settings.showCompletionPreview)
        assertFalse(settings.showHoverPopup)
        assertFalse(settings.showApiCardsOnHover)
        assertFalse(settings.exposeMcpTools)
        assertFalse(settings.allowMcpChanges)
    }
}
