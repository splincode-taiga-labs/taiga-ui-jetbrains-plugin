package org.taigaui.designtokens.resolution

internal data class ParsedDesignTokenValue(
    val rawValue: String,
    val parts: List<DesignTokenValuePart>,
)

internal sealed interface DesignTokenValuePart {
    data class Text(
        val value: String,
    ) : DesignTokenValuePart

    data class Reference(
        val name: String,
        val fallback: ParsedDesignTokenValue?,
    ) : DesignTokenValuePart
}

internal sealed interface DesignTokenValueParseResult {
    data class Parsed(
        val value: ParsedDesignTokenValue,
    ) : DesignTokenValueParseResult

    data class Invalid(
        val offset: Int,
        val message: String,
    ) : DesignTokenValueParseResult
}

internal object DesignTokenValueParser {
    fun parse(value: String): DesignTokenValueParseResult =
        try {
            DesignTokenValueParseResult.Parsed(Parser(value).parse())
        } catch (failure: ParseFailure) {
            DesignTokenValueParseResult.Invalid(
                offset = failure.offset,
                message = failure.message,
            )
        }

    private class Parser(
        private val input: String,
    ) {
        fun parse(): ParsedDesignTokenValue = parseRange(0, input.length, trim = false)

        private fun parseRange(
            initialStart: Int,
            initialEnd: Int,
            trim: Boolean,
        ): ParsedDesignTokenValue {
            val (start, end) =
                if (trim) {
                    trimmedRange(initialStart, initialEnd)
                } else {
                    initialStart to initialEnd
                }
            val parts = mutableListOf<DesignTokenValuePart>()
            var textStart = start
            var index = start

            while (index < end) {
                when {
                    input[index].isQuote() -> index = findQuotedEnd(index, end) + 1
                    input.startsComment(index, end) -> index = findCommentEnd(index, end)
                    isVarFunctionAt(index, end) -> {
                        addText(parts, textStart, index)

                        val openParenthesis = index + VAR_FUNCTION_NAME.length
                        val closeParenthesis = findClosingParenthesis(openParenthesis, end)

                        parts.add(
                            parseReference(
                                contentStart = openParenthesis + 1,
                                contentEnd = closeParenthesis,
                            ),
                        )

                        index = closeParenthesis + 1
                        textStart = index
                    }

                    else -> index++
                }
            }

            addText(parts, textStart, end)

            return ParsedDesignTokenValue(
                rawValue = input.substring(start, end),
                parts = parts.toList(),
            )
        }

        private fun parseReference(
            contentStart: Int,
            contentEnd: Int,
        ): DesignTokenValuePart.Reference {
            val comma = findTopLevelComma(contentStart, contentEnd)
            val nameEnd = comma ?: contentEnd
            val name = input.substring(contentStart, nameEnd).trim()

            if (!name.isCustomPropertyName()) {
                throw parseFailure(contentStart, "var() must reference a CSS custom property name.")
            }

            val fallback = comma?.let { parseRange(it + 1, contentEnd, trim = true) }

            return DesignTokenValuePart.Reference(
                name = name,
                fallback = fallback,
            )
        }

        private fun findTopLevelComma(
            start: Int,
            end: Int,
        ): Int? {
            var depth = 0
            var comma: Int? = null
            var index = start

            while (index < end && comma == null) {
                when {
                    input[index].isQuote() -> index = findQuotedEnd(index, end) + 1
                    input.startsComment(index, end) -> index = findCommentEnd(index, end)
                    input[index] == '(' -> {
                        depth++
                        index++
                    }

                    input[index] == ')' -> {
                        depth--
                        index++
                    }

                    input[index] == ',' && depth == 0 -> comma = index
                    else -> index++
                }
            }

            return comma
        }

        private fun findClosingParenthesis(
            openParenthesis: Int,
            end: Int,
        ): Int {
            var depth = 1
            var closingParenthesis: Int? = null
            var index = openParenthesis + 1

            while (index < end && closingParenthesis == null) {
                when {
                    input[index].isQuote() -> index = findQuotedEnd(index, end) + 1
                    input.startsComment(index, end) -> index = findCommentEnd(index, end)
                    input[index] == '(' -> {
                        depth++
                        index++
                    }

                    input[index] == ')' -> {
                        depth--

                        if (depth == 0) {
                            closingParenthesis = index
                        } else {
                            index++
                        }
                    }

                    else -> index++
                }
            }

            return closingParenthesis ?: throw parseFailure(openParenthesis, "Unterminated var() expression.")
        }

        private fun findQuotedEnd(
            quoteStart: Int,
            end: Int,
        ): Int {
            val quote = input[quoteStart]
            var escaped = false
            var quotedEnd: Int? = null
            var index = quoteStart + 1

            while (index < end && quotedEnd == null) {
                val character = input[index]

                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == quote -> quotedEnd = index
                }

                index++
            }

            return quotedEnd ?: throw parseFailure(quoteStart, "Unterminated quoted string in token value.")
        }

        private fun findCommentEnd(
            commentStart: Int,
            end: Int,
        ): Int {
            val commentEnd = input.indexOf("*/", startIndex = commentStart + 2)

            if (commentEnd < 0 || commentEnd + 2 > end) {
                throw parseFailure(commentStart, "Unterminated comment in token value.")
            }

            return commentEnd + 2
        }

        private fun isVarFunctionAt(
            index: Int,
            end: Int,
        ): Boolean {
            val functionEnd = index + VAR_FUNCTION_NAME.length
            val hasFunctionName =
                functionEnd < end &&
                    input.regionMatches(
                        thisOffset = index,
                        other = VAR_FUNCTION_NAME,
                        otherOffset = 0,
                        length = VAR_FUNCTION_NAME.length,
                        ignoreCase = true,
                    ) &&
                    input[functionEnd] == '('
            val hasBoundary = index == 0 || !input[index - 1].isIdentifierCharacter()

            return hasFunctionName && hasBoundary
        }

        private fun addText(
            parts: MutableList<DesignTokenValuePart>,
            start: Int,
            end: Int,
        ) {
            if (start < end) {
                parts.add(DesignTokenValuePart.Text(input.substring(start, end)))
            }
        }

        private fun trimmedRange(
            initialStart: Int,
            initialEnd: Int,
        ): Pair<Int, Int> {
            var start = initialStart
            var end = initialEnd

            while (start < end && input[start].isWhitespace()) {
                start++
            }

            while (end > start && input[end - 1].isWhitespace()) {
                end--
            }

            return start to end
        }
    }

    private class ParseFailure(
        val offset: Int,
        override val message: String,
    ) : RuntimeException(message)

    private const val VAR_FUNCTION_NAME = "var"

    private fun parseFailure(
        offset: Int,
        message: String,
    ): ParseFailure = ParseFailure(offset, message)

    private fun Char.isQuote(): Boolean = this == '\'' || this == '"'

    private fun Char.isIdentifierCharacter(): Boolean = isLetterOrDigit() || this == '-' || this == '_'

    private fun String.startsComment(
        index: Int,
        end: Int,
    ): Boolean = index + 1 < end && this[index] == '/' && this[index + 1] == '*'

    private fun String.isCustomPropertyName(): Boolean =
        length > 2 &&
            startsWith("--") &&
            none { character -> character.isWhitespace() || character == ',' || character == '(' || character == ')' }
}
