package org.taigaui.designtokens.documentation

import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.Timer

internal fun copyAction(
    title: String,
    value: String,
): JButton =
    JButton(title).apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        toolTipText = value
        getAccessibleContext().accessibleName = title
        val feedback = Timer(2_000) { text = title }.apply { isRepeats = false }
        addActionListener {
            CopyPasteManager.getInstance().setContents(StringSelection(value))
            text = "Copied"
            feedback.restart()
        }
        bind("ENTER", "copy-documentation-text") { doClick() }
    }
