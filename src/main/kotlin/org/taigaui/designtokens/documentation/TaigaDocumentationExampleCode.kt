package org.taigaui.designtokens.documentation

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JScrollPane

/** Keep documentation line breaks and indentation instead of HTML soft-wrapping source code. */
internal fun documentationExampleCodeBlock(
    code: String,
    width: Int = 450,
): JComponent =
    RoundedRowPanel().apply {
        layout = BorderLayout(JBUI.scale(8), 0)
        border = JBUI.Borders.empty(9, 10)
        alignmentX = JComponent.LEFT_ALIGNMENT
        val formatted = formattedDocumentationExample(code)
        val label =
            JBLabel("<html><pre>${documentationCodeHtml(formatted)}</pre></html>").apply {
                font = taigaDocumentationCodeFont()
                getAccessibleContext().accessibleName = formatted
            }
        val codeWidth = JBUI.scale(width)
        add(
            JBScrollPane(label).apply {
                border = JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
                val scrollbarHeight =
                    if (label.preferredSize.width > codeWidth) horizontalScrollBar.preferredSize.height else 0
                preferredSize = Dimension(codeWidth, label.preferredSize.height + scrollbarHeight)
            },
            BorderLayout.CENTER,
        )
    }

