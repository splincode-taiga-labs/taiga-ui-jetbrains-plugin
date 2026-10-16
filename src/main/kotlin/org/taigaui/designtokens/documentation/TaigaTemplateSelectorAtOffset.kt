package org.taigaui.designtokens.documentation

internal object TaigaTemplateSelectorAtOffset {
    fun find(
        text: CharSequence,
        offset: Int,
    ): String? =
        tagContextAt(text, offset)
            ?.let { context -> tokenAt(context.body, context.localOffset) }
            ?.takeUnless { token -> token.isInsideQuotedValue }
            ?.takeUnless { token -> token.isAttributeValue }
            ?.value
            ?.takeIf { value -> value.isTaigaSelector() }

    internal fun isTaigaDocumentationKey(value: String): Boolean =
        value.isTaigaSelector() ||
            (value.startsWith("Tui") && value.length > 3 && value[3].isUpperCase())

    private fun tagContextAt(
        text: CharSequence,
        offset: Int,
    ): TagContext? {
        if (text.isEmpty() || offset !in 0..text.length) {
            return null
        }

        val probe = offset.coerceAtMost(text.length - 1)
        val tagStart = text.lastIndexOf('<', probe)
        val previousTagEnd = text.lastIndexOf('>', probe)
        val tagEnd = text.indexOf('>', probe)

        return if (tagStart >= 0 && previousTagEnd <= tagStart && tagEnd >= 0) {
            val bodyStart = tagStart + 1
            val body = text.subSequence(bodyStart, tagEnd)

            TagContext(
                body = body,
                localOffset = (offset - bodyStart).coerceIn(0, body.length),
            )
        } else {
            null
        }
    }

    private fun tokenAt(
        body: CharSequence,
        offset: Int,
    ): Token? {
        val probe =
            when {
                offset < body.length && body[offset].isNamePart() -> offset
                offset > 0 && body[offset - 1].isNamePart() -> offset - 1
                else -> -1
            }

        return probe
            .takeIf { it >= 0 }
            ?.let { index ->
                val start =
                    generateSequence(index) { current -> (current - 1).takeIf { it >= 0 } }
                        .takeWhile { current -> body[current].isNamePart() }
                        .last()
                val end =
                    generateSequence(index) { current -> (current + 1).takeIf { it < body.length } }
                        .takeWhile { current -> body[current].isNamePart() }
                        .last() + 1

                Token(
                    body = body,
                    value = body.subSequence(start, end).toString(),
                    start = start,
                )
            }
    }

    private val Token.isInsideQuotedValue: Boolean
        get() {
            var activeQuote: Char? = null

            for (index in 0 until start) {
                val char = body[index]

                activeQuote =
                    when {
                        activeQuote == null && char.isQuote() -> char
                        activeQuote == char -> null
                        else -> activeQuote
                    }
            }

            return activeQuote != null
        }

    private val Token.isAttributeValue: Boolean
        get() =
            (start - 1 downTo 0)
                .firstOrNull { index -> !body[index].isWhitespace() }
                ?.let { index -> body[index] == '=' }
                ?: false

    private fun Char.isQuote(): Boolean = this == '"' || this == '\''

    private fun Char.isNamePart(): Boolean = isLetterOrDigit() || this in charArrayOf('_', ':', '-', '.')

    private fun String.isTaigaSelector(): Boolean =
        startsWith("tui-") ||
            (startsWith("tui") && length > 3 && (this[3].isUpperCase() || this[3].isDigit()))

    private data class TagContext(
        val body: CharSequence,
        val localOffset: Int,
    )

    private data class Token(
        val body: CharSequence,
        val value: String,
        val start: Int,
    )
}
