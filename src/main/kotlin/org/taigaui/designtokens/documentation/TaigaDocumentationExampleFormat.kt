package org.taigaui.designtokens.documentation

/** Reflows flattened single-element examples for display; the original snapshot stays intact. */
@Suppress("ReturnCount")
internal fun formattedDocumentationExample(code: String): String {
    if (code.contains('\n') || code.length < 80) return code
    val element = SINGLE_ELEMENT.matchEntire(code.trim()) ?: return code
    val tag = element.groupValues[1]
    if (tag.lowercase() in setOf("pre", "textarea", "script", "style")) return code
    val attributes = ATTRIBUTES.findAll(element.groupValues[2]).map { it.value.trim() }.toList()
    if (attributes.size < 2) return code
    return buildString {
        append('<').append(tag).append('\n')
        attributes.forEach { append("    ").append(it).append('\n') }
        append(">\n    ")
            .append(element.groupValues[3].trim())
            .append("\n</")
            .append(tag)
            .append('>')
    }
}

private val SINGLE_ELEMENT = Regex("""<([\w:-]+)((?:[^>"']|"[^"]*"|'[^']*')*)>([^<]*)</\1>""")
private val ATTRIBUTES = Regex("""[^\s=]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s]+))?""")
