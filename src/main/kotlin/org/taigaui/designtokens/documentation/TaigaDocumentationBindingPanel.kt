package org.taigaui.designtokens.documentation

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal class TaigaDocumentationBindingPanel(
    private val member: TaigaResolvedDocumentation.Member,
    private val applyValue: ((String) -> String)?,
    currentValue: String? = null,
    private val resize: () -> Unit = {},
) : JPanel() {
    val editorContent =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }

    private val canApply =
        applyValue != null && member.kind == TaigaApiMemberKind.INPUT && member.binding?.literal != null
    private val values =
        member.localValues().ifEmpty {
            member.possibleValues().ifEmpty {
                member.property.documentedType
                    ?.let(::finiteStringValues)
                    .orEmpty()
            }
        }
    private var currentValue = currentValue ?: member.binding?.literal
    private val current = JBLabel().apply { alignmentX = LEFT_ALIGNMENT }
    private val status =
        JBLabel().apply {
            alignmentX = LEFT_ALIGNMENT
            isVisible = false
        }
    private val choices = ComboBox(values.toTypedArray())
    private val apply = JButton("Apply")
    private var contextCurrent = true

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        updateCurrentValue()
        if (member.binding != null) editorContent.add(current)
        if (values.isNotEmpty()) {
            val valueType = values.joinToString(" | ") { "'$it'" }
            if (!sameDocumentationType(valueType, member.typeText) &&
                !sameDocumentationType(valueType, member.localMember?.expandedType)
            ) {
                val presentation =
                    documentationUnionChips(valueType)
                        ?: documentationCodeLabel(compactDocumentationType(valueType))
                add(documentationRow("Possible values", presentation))
            }
            if (canApply) {
                editorContent.add(Box.createVerticalStrut(JBUI.scale(6)))
                choices.apply {
                    getAccessibleContext().accessibleName = "Allowed input values"
                    preferredSize = Dimension(JBUI.scale(220), preferredSize.height)
                    this@TaigaDocumentationBindingPanel.currentValue?.takeIf { it in values }?.let { selectedItem = it }
                    addActionListener { updateActions() }
                }
                if (values.size > SEARCHABLE_VALUES_THRESHOLD) editorContent.add(valueSearch())
                editorContent.add(valueActions())
            }
            add(status)
            updateActions()
        }
    }

    fun invalidateContext() {
        contextCurrent = false
        showStatus("Refresh to apply.")
        updateActions()
    }

    private fun updateActions() {
        val value = choices.selectedItem as? String
        apply.isEnabled = canApply && contextCurrent && value != null && value != currentValue
        apply.toolTipText =
            when {
                !contextCurrent -> STALE_DOCUMENTATION_MESSAGE
                !canApply -> member.valueActionUnavailableReason()
                value == currentValue -> "This value is already applied"
                else -> "Apply the selected value to this binding"
            }
        choices.toolTipText = value
    }

    private fun valueActions(): JPanel =
        JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(
                JBLabel("Value").apply {
                    foreground = CARD_MUTED_FOREGROUND
                    font = font.deriveFont(Font.PLAIN)
                },
                BorderLayout.WEST,
            )
            add(choices, BorderLayout.CENTER)
            add(
                JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    add(apply)
                },
                BorderLayout.EAST,
            )
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            apply.addActionListener {
                val value = choices.selectedItem as? String ?: return@addActionListener
                val result = applyValue?.invoke(value).orEmpty()
                if (result.startsWith("Applied")) {
                    currentValue = value
                    updateCurrentValue()
                    updateActions()
                }
                showStatus(result)
            }
            apply.bind("ENTER", "apply-input-value") { if (apply.isEnabled) apply.doClick() }
        }

    private fun valueSearch(): JBTextField =
        JBTextField().apply {
            emptyText.text = "Search all ${values.size} values"
            getAccessibleContext().accessibleName = "Search allowed input values"
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            document.addDocumentListener(
                object : DocumentListener {
                    private fun filter() {
                        val selected = choices.selectedItem as? String
                        val filtered = values.filter { it.contains(text.trim(), ignoreCase = true) }
                        choices.model = DefaultComboBoxModel(filtered.toTypedArray())
                        if (selected != null && selected in filtered) choices.selectedItem = selected
                        updateActions()
                        showStatus("${filtered.size} of ${values.size} values")
                    }

                    override fun insertUpdate(event: DocumentEvent) = filter()

                    override fun removeUpdate(event: DocumentEvent) = filter()

                    override fun changedUpdate(event: DocumentEvent) = filter()
                },
            )
        }

    private fun updateCurrentValue() {
        current.text = "Current value: ${currentValue ?: "dynamic expression"}"
        current.foreground = CARD_MUTED_FOREGROUND
        current.font = current.font.deriveFont(Font.PLAIN)
        current.maximumSize = Dimension(Int.MAX_VALUE, current.preferredSize.height)
        current.toolTipText = current.text
    }

    private fun showStatus(message: String) {
        status.text = message
        status.toolTipText = message
        status.isVisible = message.isNotEmpty()
        status.maximumSize = Dimension(Int.MAX_VALUE, status.preferredSize.height)
        if (parent != null) resize()
    }
}

private fun TaigaResolvedDocumentation.Member.valueActionUnavailableReason(): String =
    when {
        binding?.literal == null && binding != null -> "Dynamic expression."
        binding == null -> "No existing binding."
        localValues().isEmpty() -> "Installed types do not confirm a finite set of values."
        else -> "Source editing is unavailable."
    }

private const val SEARCHABLE_VALUES_THRESHOLD = 12
