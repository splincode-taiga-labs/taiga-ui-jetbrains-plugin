package org.taigaui.designtokens.documentation

/** A pipe inside inline code is a TypeScript union, not a Markdown column separator. */
internal fun documentationTableCells(line: String): List<String> {
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    val text = line.removePrefix("|").removeSuffix("|")
    var codeDelimiter = 0
    var position = 0
    TABLE_MARKERS.findAll(text).forEach { marker ->
        cell.append(text.substring(position, marker.range.first))
        val token = marker.value
        when {
            token.startsWith('`') -> {
                codeDelimiter =
                    when (codeDelimiter) {
                        0 -> token.length
                        token.length -> 0
                        else -> codeDelimiter
                    }
                cell.append(token)
            }
            token.startsWith('\\') || codeDelimiter > 0 -> cell.append('|')
            else -> {
                cells += cell.toString().trim()
                cell.setLength(0)
            }
        }
        position = marker.range.last + 1
    }
    cell.append(text.substring(position))
    cells += cell.toString().trim()
    return cells
}

private val TABLE_MARKERS = Regex("""`+|\\+\||\|""")
