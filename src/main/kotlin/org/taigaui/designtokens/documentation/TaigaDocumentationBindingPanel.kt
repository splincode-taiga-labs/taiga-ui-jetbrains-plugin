package org.taigaui.designtokens.documentation

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal class TaigaDocumentationBindingPanel(
    member: TaigaResolvedDocumentation.Member,
    applyValue: ((String) -> String)?,
    currentValue: String? = null,
    private val resize: () -> Unit = {},
) : JPanel() {
    private val canApply =
        applyValue != null && member.kind == TaigaApiMemberKind.INPUT && member.binding?.literal != null
    private val prompt = JBLabel()
    private val applyButtons = mutableListOf<JButton>()
    private var contextCurrent = true

    fun invalidateContext() {
        contextCurrent = false
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
            val choices =
                JPanel(GridLayout(0, VALUE_COLUMNS, JBUI.scale(6), JBUI.scale(4))).apply {
                    isOpaque = false
                    alignmentX = LEFT_ALIGNMENT
                }

            fun showValues(visible: List<String>) {
                applyButtons.clear()
                choices.removeAll()
                visible.forEach { value ->
                    choices.add(
                        JPanel().apply {
                            isOpaque = false
                            layout = BoxLayout(this, BoxLayout.Y_AXIS)
                            add(JBLabel("'$value'"))
                            add(
                                JButton("Apply '$value'").apply {
                                    applyButtons += this
                                    isEnabled = canApply && contextCurrent
                                    toolTipText =
                                        if (!contextCurrent) {
                                            STALE_DOCUMENTATION_MESSAGE
                                        } else if (canApply) {
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
                                    bind("ENTER", "apply-input-value") { if (isEnabled) doClick() }
                                },
                            )
                            add(
                                JButton("Copy '$value'").apply {
                                    toolTipText = "Copy '$value'"
                                    addActionListener { copyLiteral(value, status) }
                                    bind("ENTER", "copy-input-value") { doClick() }
                                },
                            )
                        },
                    )
                }
                choices.revalidate()
                choices.repaint()
                if (choices.parent != null) resize()
            }
            showValues(values.take(MAX_VISIBLE_VALUES))
            add(choices)
            if (values.size > MAX_VISIBLE_VALUES) {
                val search =
                    JBTextField().apply {
                        emptyText.text = "Search all ${values.size} values"
                        getAccessibleContext().accessibleName = "Search allowed input values"
                        isVisible = false
                        alignmentX = LEFT_ALIGNMENT
                        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                    }
                search.document.addDocumentListener(
                    object : DocumentListener {
                        private fun filter() {
                            val filtered = values.filter { it.contains(search.text.trim(), ignoreCase = true) }
                            showValues(filtered)
                            status.text = "${filtered.size} of ${values.size} values"
                        }

                        override fun insertUpdate(event: DocumentEvent) = filter()

                        override fun removeUpdate(event: DocumentEvent) = filter()

                        override fun changedUpdate(event: DocumentEvent) = filter()
                    },
                )
                add(search)
                add(
                    JButton("Show all (${values.size})").apply {
                        alignmentX = LEFT_ALIGNMENT
                        addActionListener {
                            isVisible = false
                            search.isVisible = true
                            showValues(values)
                            search.requestFocusInWindow()
                        }
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
