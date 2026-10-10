package org.taigaui.designtokens.documentation

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

/** Explicit requests always acknowledge loading, indexing and an unavailable caret target. */
internal class TaigaDocumentationStatusPanel(
    message: String,
    onClose: () -> Unit,
    showQuickFixes: () -> Unit,
) : JPanel(BorderLayout(0, JBUI.scale(12))) {
    private val status = JBLabel(message)
    private val quickFixes =
        JButton("Angular quick fixes").apply {
            isVisible = false
            addActionListener { showQuickFixes() }
            bind("ENTER", "show-angular-quick-fixes") { doClick() }
        }
    val closeButton =
        JButton("Close").apply {
            addActionListener { onClose() }
            bind("ENTER", "close-documentation-status") { doClick() }
        }

    init {
        background = DESIGN_TOKEN_POPUP_BACKGROUND
        border = JBUI.Borders.empty(16, 18)
        getAccessibleContext().accessibleName = "Taiga UI documentation status"
        bind("ESCAPE", "close-documentation-status", onClose)
        add(status, BorderLayout.CENTER)
        add(
            JPanel().apply {
                isOpaque = false
                add(quickFixes)
                add(closeButton)
            },
            BorderLayout.SOUTH,
        )
    }

    fun showUnavailable() {
        status.text = "No installed Taiga UI API at the caret."
        quickFixes.isVisible = true
        revalidate()
        repaint()
    }

    fun showLoading() {
        status.text = "Loading Taiga UI API…"
    }
}
