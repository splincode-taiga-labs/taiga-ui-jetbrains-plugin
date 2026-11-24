package org.taigaui.designtokens.documentation

import com.intellij.ui.components.JBLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane

class TaigaQuickDocumentationAdditionalPanelTest : TaigaDocumentationPopupTestCase() {
    fun testOutputOnlyDirectiveShowsOutputsWithoutEmptyInputsTab() {
        val owner = directive()
        val output = TaigaApiProperty("valueChange", "(valueChange)", "string", "Emits the new value")
        val resolved = owner.copy(entity = owner.entity.copy(inputs = emptyList(), outputs = listOf(output)))
        val panel = TaigaQuickDocumentationPopupPanel(resolved)
        val tabs = descendants(panel).filterIsInstance<JTabbedPane>().single()

        assertEquals(1, tabs.tabCount)
        assertEquals("Outputs (1)", tabs.getTitleAt(0))
        assertTrue(labelText(tabs.getComponentAt(0)).contains("valueChange"))
    }

    fun testFormattedUsageReplacesOriginalAndCanBeSuppressed() {
        val owner = directive()
        val panel = TaigaQuickDocumentationPopupPanel(owner)
        val formatted = "<button tuiButton>Formatted</button>"

        panel.finishUsageFormatting(formatted)
        assertTrue(hasUsage(panel, formatted))

        panel.finishUsageFormatting(null, suppress = true)
        assertFalse(hasUsage(panel, formatted))

        panel.finishUsageFormatting(formatted)
        assertFalse(hasUsage(panel, formatted))
    }

    fun testAsyncIconPreviewReplacesPreviousPreviewContent() {
        val owner = component()
        val reference = TaigaDocumentationIcon("icon", "@tui.eye", 0, 8)
        val resolved = owner.copy(icons = listOf(reference))
        val panel = TaigaQuickDocumentationPopupPanel(resolved)
        val preview = iconPreview(reference)
        val container = documentationField<JPanel>(panel, "previewContent")

        panel.showIconPreviews(listOf(preview))
        assertTrue(descendants(container).filterIsInstance<JBLabel>().any { it.icon === preview.icon })

        panel.showIconPreviews(emptyList())
        assertFalse(descendants(container).filterIsInstance<JBLabel>().any { it.icon === preview.icon })
    }

    private fun hasUsage(
        panel: TaigaQuickDocumentationPopupPanel,
        code: String,
    ): Boolean = descendants(panel).filterIsInstance<JBLabel>().any { it.accessibleContext.accessibleName == code }
}
