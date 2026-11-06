package org.taigaui.designtokens.documentation

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar

/** Owns the transient state of the Examples tab; other tabs remain interactive while formatting. */
internal class TaigaDocumentationExampleState(
    private var loading: Boolean,
    private val onSizeChanged: () -> Unit,
) {
    private var tabs: TaigaDocumentationTabs? = null
    private var tabIndex = -1
    private var standalone: JPanel? = null

    fun addTab(
        owner: TaigaDocumentationTabs,
        code: String,
    ) {
        val index = owner.tabCount
        owner.addTab("Examples", content(code))
        tabs = owner
        tabIndex = index
        if (loading) {
            owner.setEnabledAt(index, false)
            owner.setTabComponentAt(index, progressTab())
        }
    }

    fun standalone(code: String): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(content(code), BorderLayout.CENTER)
            standalone = this
        }

    fun finish(code: String) {
        if (!loading) return
        loading = false
        tabs?.takeIf { tabIndex in 0 until it.tabCount }?.let { owner ->
            owner.setComponentAt(tabIndex, documentationExampleCodeBlock(code, 450))
            owner.setEnabledAt(tabIndex, true)
            owner.setTabComponentAt(tabIndex, null)
        }
        standalone?.let { container ->
            container.removeAll()
            container.add(documentationExampleCodeBlock(code, 450), BorderLayout.CENTER)
        }
        onSizeChanged()
    }

    private fun content(code: String): JComponent =
        if (loading) progressContent() else documentationExampleCodeBlock(code, 450)

    private fun progressTab(): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(JBLabel("Examples").apply { foreground = CARD_MUTED_FOREGROUND })
            add(progressBar())
            getAccessibleContext().accessibleName = "Formatting Examples"
        }

    private fun progressContent(): JComponent =
        JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JBLabel("Formatting example…"), BorderLayout.WEST)
            add(progressBar(), BorderLayout.CENTER)
        }

    private fun progressBar(): JProgressBar =
        JProgressBar().apply {
            isIndeterminate = true
            isOpaque = false
            isFocusable = false
            preferredSize = JBUI.size(28, 6)
            getAccessibleContext().accessibleName = "Formatting example"
        }
}
