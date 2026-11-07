package org.taigaui.designtokens.documentation

/**
 * Deterministic fallback when project-local Prettier is absent or cannot format an example.
 * Wraps attributes of one opening HTML tag without modifying bindings, text nodes or nesting.
 */
@Suppress("ReturnCount")
internal fun formattedDocumentationExample(code: String): String {
    if (code.length > MAX_CUSTOM_EXAMPLE_LENGTH || '\n' in code) return code
    val match = OPENING_TAG.find(code) ?: return code
    val name = match.groupValues[1]
    val end = openingTagEnd(code, match.range.last + 1) ?: return code
    val attributesText = code.substring(match.range.last + 1, end)
    val selfClosing = attributesText.trimEnd().endsWith('/')
    val attributes =
        htmlAttributes(
            if (selfClosing) attributesText.trimEnd().dropLast(1) else attributesText,
        ) ?: return code
    if (attributes.size < 2) return code

    val formatted =
        buildString {
            append('<').append(name)
            attributes.forEach { attribute -> append("\n    ").append(attribute) }
            append(if (selfClosing) "\n/>" else "\n>")
            append(code.substring(end + 1))
        }
    return formatted.takeIf { documentationExamplePreservesContent(code, it) } ?: code
}

private fun openingTagEnd(
    code: String,
    start: Int,
): Int? {
    var quote: Char? = null
    for (index in start until code.length) {
        when (code[index]) {
            '"', '\'' -> {
                if (quote == null) quote = code[index] else if (quote == code[index]) quote = null
            }
            '>' -> if (quote == null) return index
        }
    }
    return null
}

private fun htmlAttributes(text: String): List<String>? {
    val attributes = mutableListOf<String>()
    val token = StringBuilder()
    var quote: Char? = null
    text.forEach { char ->
        when {
            quote != null -> {
                token.append(char)
                if (char == quote) quote = null
            }
            char == '"' || char == '\'' -> {
                token.append(char)
                quote = char
            }
            char.isWhitespace() -> {
                if (token.isNotEmpty()) {
                    attributes.add(token.toString())
                    token.clear()
                }
            }
            else -> token.append(char)
        }
    }
    if (quote != null) return null
    if (token.isNotEmpty()) attributes.add(token.toString())
    return attributes.takeIf { values -> values.none { it == "=" } }
}

private val OPENING_TAG = Regex("""^<([A-Za-z][\w:-]*)""")
private const val MAX_CUSTOM_EXAMPLE_LENGTH = 4_000
