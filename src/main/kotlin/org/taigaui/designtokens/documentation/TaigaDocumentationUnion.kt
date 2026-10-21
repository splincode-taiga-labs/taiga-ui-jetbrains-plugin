package org.taigaui.designtokens.documentation

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Display finite literal unions as readable, wrapping alternatives.
 * Unknown, mixed and expression types keep the original syntax-highlighted code.
 */
internal fun documentationUnionChips(
    type: String,
    width: Int = 370,
    maxRows: Int = Int.MAX_VALUE,
): JComponent? {
    val alternatives = type.trim().split('|').map(String::trim)
    if (alternatives.size < 2 || alternatives.any { DISPLAY_LITERAL.matchEntire(it) == null }) return null

    val container =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = JComponent.LEFT_ALIGNMENT
            getAccessibleContext().accessibleName = type
        }
    val rows = documentationUnionRows(container, alternatives, width)
    rows.take(maxRows.coerceAtLeast(1)).forEach { items ->
        container.add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), JBUI.scale(2))).apply {
                isOpaque = false
                alignmentX = JComponent.LEFT_ALIGNMENT
                items.forEach { add(documentationUnionChip(it)) }
            },
        )
    }
    val hidden = alternatives.size - rows.take(maxRows.coerceAtLeast(1)).sumOf(List<String>::size)
    if (hidden > 0) {
        container.add(
            JBLabel("+$hidden more").apply {
                foreground = CARD_MUTED_FOREGROUND
                alignmentX = JComponent.LEFT_ALIGNMENT
                toolTipText = type
            },
        )
    }
    return container
}

private fun documentationUnionRows(
    component: JComponent,
    alternatives: List<String>,
    width: Int,
): List<List<String>> {
    val font = taigaDocumentationCodeFont()
    val metrics = component.getFontMetrics(font)
    val limit = JBUI.scale(width).coerceAtLeast(JBUI.scale(40))
    val gap = JBUI.scale(5)
    val padding = JBUI.scale(18)
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    var used = 0

    alternatives.forEach { value ->
        val itemWidth = metrics.stringWidth(value) + padding + gap
        if (row.isNotEmpty() && used + itemWidth > limit) {
            rows.add(row)
            row = mutableListOf()
            used = 0
        }
        row.add(value)
        used += itemWidth
    }
    if (row.isNotEmpty()) rows.add(row)
    return rows
}

private fun documentationUnionChip(value: String): JComponent =
    RoundedRowPanel().apply {
        border = JBUI.Borders.empty(3, 7)
        add(
            JBLabel("<html>${documentationCodeHtml(value)}</html>").apply {
                font = taigaDocumentationCodeFont()
                getAccessibleContext().accessibleName = value
                toolTipText = value
            },
        )
    }

private val DISPLAY_LITERAL = Regex("""(["'])([^"']*)\1""")
