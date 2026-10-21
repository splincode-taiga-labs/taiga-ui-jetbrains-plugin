package org.taigaui.designtokens.documentation

import com.intellij.util.ui.JBUI
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JPanel

internal fun JPanel.addCompactHoverContent(
    resolved: TaigaResolvedDocumentation,
    metadata: JComponent,
    previews: JPanel,
) {
    resolved.description?.takeIf(String::isNotBlank)?.let { description ->
        add(Box.createVerticalStrut(JBUI.scale(8)))
        add(wrappedLabel(description.take(MAX_HOVER_DESCRIPTION_LENGTH), COMPACT_CONTENT_WIDTH - 24))
    }
    if (resolved is TaigaResolvedDocumentation.Member) {
        resolved.typeText?.takeIf(String::isNotBlank)?.let { type ->
            add(Box.createVerticalStrut(JBUI.scale(8)))
            val width = COMPACT_CONTENT_WIDTH - 24
            add(
                documentationUnionChips(type, width, maxRows = 2)
                    ?: documentationCodeLabel(type.take(MAX_HOVER_TYPE_LENGTH), width),
            )
        }
        resolved.binding?.let { binding ->
            add(Box.createVerticalStrut(JBUI.scale(8)))
            val value = binding.literal ?: "dynamic expression"
            add(wrappedLabel("Current value: ${value.take(MAX_HOVER_VALUE_LENGTH)}", COMPACT_CONTENT_WIDTH - 24))
        }
        resolved.receivers.map { it.subject.presentationName }.distinct().takeIf { it.size > 1 }?.let { owners ->
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(wrappedLabel(owners.joinToString(" · "), COMPACT_CONTENT_WIDTH - 24))
        }
    }
    add(Box.createVerticalStrut(JBUI.scale(10)))
    add(metadata)
    if (resolved.documentationIcons.isNotEmpty()) add(previews)
}

private const val MAX_HOVER_VALUE_LENGTH = 70
