package org.taigaui.designtokens.documentation

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Render simple types and top-level unions as wrapping chips.
 * Complex standalone types retain their original code presentation.
 */
internal fun documentationUnionChips(
    type: String,
    width: Int = 370,
    maxRows: Int = Int.MAX_VALUE,
): JComponent? {
    val alternatives = documentationTypeTokens(type)
    if (alternatives.isEmpty()) return null

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
    if (documentationHasOmittedValues(type)) {
        container.add(
            JBLabel("Further values are not specified in the source").apply {
                foreground = CARD_MUTED_FOREGROUND
                alignmentX = JComponent.LEFT_ALIGNMENT
                toolTipText = type
            },
        )
    }
    return container
}

internal fun documentationTypeTokens(type: String): List<String> {
    val alternatives = documentationUnionAlternatives(type)
    if (alternatives.isNotEmpty()) return alternatives.filterNot(::isDocumentationOmission)

    val single = cleanDocumentationType(type)
    return if (!isDocumentationOmission(single) && SIMPLE_DOCUMENTATION_TYPE.matches(single)) {
        listOf(single)
    } else {
        emptyList()
    }
}

internal fun documentationHasOmittedValues(type: String): Boolean =
    documentationUnionAlternatives(type).any(::isDocumentationOmission)

private fun isDocumentationOmission(value: String): Boolean = value == "..." || value == "…"

/** Strip a stray closing parenthesis from a simple IDE-extracted type, never from a function type. */
internal fun cleanDocumentationType(type: String): String {
    val trimmed = type.trim()
    return STRAY_SIMPLE_TYPE_PARENTHESIS.matchEntire(trimmed)?.groupValues?.get(1) ?: trimmed
}

private val STRAY_SIMPLE_TYPE_PARENTHESIS = Regex("""^([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\)+$""")

private val SIMPLE_DOCUMENTATION_TYPE =
    Regex("""(?:[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*|["'][^"']*["']|-?\d+(?:\.\d+)?)""")

/** Split only top-level unions, preserving nested TypeScript expressions and quoted pipes. */
@Suppress("CyclomaticComplexMethod", "ReturnCount")
internal fun documentationUnionAlternatives(type: String): List<String> {
    val alternatives = mutableListOf<String>()
    val closing = mutableListOf<Char>()
    var quote: Char? = null
    var escaped = false
    var start = 0

    type.forEachIndexed { index, character ->
        if (quote != null) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == quote -> quote = null
            }
        } else {
            when (character) {
                '\'', '"', '\u0060' -> quote = character
                '(' -> closing.add(')')
                '[' -> closing.add(']')
                '{' -> closing.add('}')
                '<' -> closing.add('>')
                ')', ']', '}', '>' -> {
                    if (closing.lastOrNull() == character) {
                        closing.removeAt(closing.lastIndex)
                    } else if (character != '>') {
                        return emptyList()
                    }
                }

                '|' -> {
                    if (closing.isNotEmpty()) return@forEachIndexed
                    if (type.getOrNull(index - 1) == '|' || type.getOrNull(index + 1) == '|') {
                        return emptyList()
                    }
                    val alternative = type.substring(start, index).trim()
                    if (alternative.isEmpty()) return emptyList()
                    alternatives.add(alternative)
                    start = index + 1
                }
            }
        }
    }
    if (quote != null || closing.isNotEmpty() || alternatives.isEmpty()) return emptyList()
    val last = type.substring(start).trim()
    return if (last.isEmpty()) emptyList() else alternatives + last
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
