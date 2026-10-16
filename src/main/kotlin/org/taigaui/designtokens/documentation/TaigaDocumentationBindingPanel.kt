package org.taigaui.designtokens.documentation

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBLabel
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

internal class TaigaDocumentationBindingPanel(
    member: TaigaResolvedDocumentation.Member,
    applyValue: ((String) -> String)?,
    currentValue: String? = null,
) : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        val values = member.localValues().ifEmpty { member.possibleValues() }
        val status = JBLabel()
        val canApply = applyValue != null && member.kind == TaigaApiMemberKind.INPUT && member.binding?.literal != null
        val current = JBLabel()
        member.binding?.let { binding ->
            current.text = if (binding.literal != null) "Current value: ${currentValue ?: binding.literal}" else "Current value: dynamic expression"
            add(current)
        }
        if (values.isNotEmpty()) {
            add(JBLabel(if (canApply) "Choose a value to apply" else "Choose a literal to copy"))
            add(
                JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    isOpaque = false
                    values.forEach { value ->
                        add(
                            JButton(value).apply {
                                toolTipText = if (canApply) "Apply $value to the existing binding" else "Copy '$value'"
                                addActionListener {
                                    if (canApply) {
                                        status.text = applyValue?.invoke(value)
                                        if (status.text?.startsWith("Applied") == true) current.text = "Current value: $value"
                                    } else {
                                        CopyPasteManager.getInstance().setContents(StringSelection("'$value'"))
                                        status.text = "Copied '$value'"
                                    }
                                }
                            },
                        )
                    }
                },
            )
            add(status)
        }
    }
}
