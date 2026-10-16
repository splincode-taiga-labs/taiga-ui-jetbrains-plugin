package org.taigaui.designtokens.documentation

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

internal class TaigaDocumentationBindingPanel(
    member: TaigaResolvedDocumentation.Member,
    applyValue: ((String) -> String)?,
    currentValue: String? = null,
) : JPanel() {
    private val canApply =
        applyValue != null && member.kind == TaigaApiMemberKind.INPUT && member.binding?.literal != null
    private val prompt = JBLabel()
    private val applyButtons = mutableListOf<JButton>()

    fun invalidateContext() {
        prompt.text = "Refresh to apply. Copy remains available."
        applyButtons.forEach {
            it.isEnabled = false
            it.toolTipText = STALE_DOCUMENTATION_MESSAGE
        }
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        val values =
            member.localValues().ifEmpty {
                member.possibleValues().ifEmpty {
                    member.property.documentedType
                        ?.let(::finiteStringValues)
                        .orEmpty()
                }
            }
        val status =
            JBLabel(" ").apply {
                alignmentX = LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            }
        val current = JBLabel().apply { alignmentX = LEFT_ALIGNMENT }
        member.binding?.let { binding ->
            current.text =
                if (binding.literal != null) {
                    "Current value: ${currentValue ?: binding.literal}"
                } else {
                    "Current value: dynamic expression"
                }
            current.maximumSize = Dimension(Int.MAX_VALUE, current.preferredSize.height)
            current.toolTipText = current.text
            add(current)
        }
        if (values.isNotEmpty()) {
            prompt.text =
                if (canApply) "Allowed values" else member.valueActionUnavailableReason()
            add(
                prompt.apply {
                    alignmentX = LEFT_ALIGNMENT
                },
            )
            add(
                JPanel(GridLayout(0, VALUE_COLUMNS, JBUI.scale(6), JBUI.scale(4))).apply {
                    isOpaque = false
                    alignmentX = LEFT_ALIGNMENT
                    values.take(MAX_VISIBLE_VALUES).forEach { value ->
                        add(
                            JPanel().apply {
                                isOpaque = false
                                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                                add(JBLabel("'$value'"))
                                add(
                                    JButton("Apply '$value'").apply {
                                        applyButtons += this
                                        isEnabled = canApply
                                        toolTipText =
                                            if (canApply) {
                                                "Apply '$value' to this binding"
                                            } else {
                                                member
                                                    .valueActionUnavailableReason()
                                            }
                                        addActionListener {
                                            status.text = applyValue?.invoke(value)
                                            status.toolTipText = status.text
                                            if (status.text?.startsWith("Applied") == true) {
                                                current.text = "Current value: $value"
                                                current.toolTipText = current.text
                                            }
                                        }
                                    },
                                )
                                add(
                                    JButton("Copy '$value'").apply {
                                        toolTipText = "Copy '$value'"
                                        addActionListener { copyLiteral(value, status) }
                                    },
                                )
                            },
                        )
                    }
                },
            )
            if (values.size > MAX_VISIBLE_VALUES) {
                val more = if (member.source != null) "More values in source" else "More values in full documentation"
                add(
                    JBLabel(more).apply {
                        alignmentX = LEFT_ALIGNMENT
                    },
                )
            }
            add(status)
        }
    }

    private fun copyLiteral(
        value: String,
        status: JBLabel,
    ) {
        CopyPasteManager.getInstance().setContents(StringSelection("'$value'"))
        status.text = "Copied '$value'"
        status.toolTipText = status.text
    }
}

private fun TaigaResolvedDocumentation.Member.valueActionUnavailableReason(): String =
    when {
        binding?.literal == null && binding != null -> "Dynamic expression. Copy a value to use it manually."
        binding == null -> "No existing binding. Copy a value to use it manually."
        localValues().isEmpty() -> "Installed types do not confirm a finite set of values. Copy is available."
        else -> "Source editing is unavailable. Copy is available."
    }

private const val VALUE_COLUMNS = 3
private const val MAX_VISIBLE_VALUES = 12
