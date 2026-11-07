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
    val isLoading: Boolean get() = loading

    private var tabs: TaigaDocumentationTabs? = null
    private var tabIndex = -1
    private var standalone: JPanel? = null
    private var progressVisible = false

    fun addTab(
        owner: TaigaDocumentationTabs,
        code: String,
    ) {
        val index = owner.tabCount
        owner.addTab("Examples", content(code))
        tabs = owner
        tabIndex = index
        if (loading) owner.setEnabledAt(index, false)
    }

    fun standalone(code: String): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(content(code), BorderLayout.CENTER)
            standalone = this
        }

    fun showProgress() {
        if (!loading || progressVisible) return
        progressVisible = true
        tabs?.takeIf { tabIndex in 0 until it.tabCount }?.setTabComponentAt(tabIndex, progressTab())
        standalone?.let { container ->
            container.removeAll()
            container.add(progressContent(), BorderLayout.CENTER)
        }
        onSizeChanged()
    }

    fun finish(code: String?) {
        if (!loading) return
        loading = false
        tabs?.takeIf { tabIndex in 0 until it.tabCount }?.let { owner ->
            if (code.isNullOrBlank()) {
                owner.removeTabAt(tabIndex)
                if (owner.tabCount == 0) owner.isVisible = false
            } else {
                owner.setComponentAt(tabIndex, documentationExampleCodeBlock(code, 450))
                owner.setEnabledAt(tabIndex, true)
                owner.setTabComponentAt(tabIndex, null)
            }
        }
        standalone?.let { container ->
            container.removeAll()
            if (code.isNullOrBlank()) {
                container.isVisible = false
            } else {
                container.add(documentationExampleCodeBlock(code, 450), BorderLayout.CENTER)
            }
        }
        onSizeChanged()
    }

    private fun content(code: String): JComponent =
        if (loading) {
            JPanel().apply { isOpaque = false }
        } else {
            documentationExampleCodeBlock(formattedDocumentationExample(code), 450)
        }

    private fun progressTab(): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(JBLabel("Examples").apply { foreground = CARD_MUTED_FOREGROUND })
            add(progressBar())
            add(JBLabel("Formatting…").apply { foreground = CARD_MUTED_FOREGROUND })
            getAccessibleContext().accessibleName = "Examples: formatting in progress"
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
            preferredSize = JBUI.size(36, 8)
            getAccessibleContext().accessibleName = "Formatting example"
        }
}
