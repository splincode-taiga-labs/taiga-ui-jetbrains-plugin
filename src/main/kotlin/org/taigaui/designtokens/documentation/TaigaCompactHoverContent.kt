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
            add(wrappedLabel(type.take(MAX_HOVER_TYPE_LENGTH), COMPACT_CONTENT_WIDTH - 24))
        }
    }
    add(Box.createVerticalStrut(JBUI.scale(10)))
    add(metadata)
    if (resolved.documentationIcons.isNotEmpty()) add(previews)
}
