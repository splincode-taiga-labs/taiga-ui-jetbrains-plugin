package org.taigaui.designtokens.documentation

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.GridLayout
import java.awt.event.ActionEvent
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
        val status = JBLabel(" ").apply { alignmentX = LEFT_ALIGNMENT }
        val canApply = applyValue != null && member.kind == TaigaApiMemberKind.INPUT && member.binding?.literal != null
        val current = JBLabel().apply { alignmentX = LEFT_ALIGNMENT }
        member.binding?.let { binding ->
            current.text =
                if (binding.literal != null) "Current value: ${currentValue ?: binding.literal}" else "Current value: dynamic expression"
            add(current)
        }
        if (values.isNotEmpty()) {
            add(
                JBLabel(if (canApply) "Choose a value to apply (Shift-click copies)" else "Choose a literal to copy").apply {
                    alignmentX = LEFT_ALIGNMENT
                },
            )
            add(
                JPanel(GridLayout(0, VALUE_COLUMNS, JBUI.scale(6), JBUI.scale(4))).apply {
                    isOpaque = false
                    alignmentX = LEFT_ALIGNMENT
                    values.take(MAX_VISIBLE_VALUES).forEach { value ->
                        add(
                            JButton(value).apply {
                                toolTipText = if (canApply) "Apply $value to the existing binding; Shift-click to copy" else "Copy '$value'"
                                addActionListener { event ->
                                    if (canApply && event.modifiers and ActionEvent.SHIFT_MASK == 0) {
                                        status.text = applyValue?.invoke(value)
                                        if (status.text?.startsWith("Applied") == true) {
                                            current.text = "Current value: $value"
                                        }
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
            if (values.size > MAX_VISIBLE_VALUES) {
                add(JBLabel("More values in source").apply { alignmentX = LEFT_ALIGNMENT })
            }
            add(status)
        }
    }
}

private const val VALUE_COLUMNS = 3
private const val MAX_VISIBLE_VALUES = 12
