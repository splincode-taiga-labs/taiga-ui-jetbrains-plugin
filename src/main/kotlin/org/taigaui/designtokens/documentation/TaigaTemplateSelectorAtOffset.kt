package org.taigaui.designtokens.documentation

internal object TaigaTemplateSelectorAtOffset {
    fun find(
        text: CharSequence,
        offset: Int,
    ): String? {
        if (text.isEmpty() || offset !in 0..text.length) {
            return null
        }

        val probe = offset.coerceAtMost(text.length - 1)
        val tagStart = text.lastIndexOf('<', probe)

        if (tagStart < 0 || text.lastIndexOf('>', probe) > tagStart) {
            return null
        }

        val tagEnd = text.indexOf('>', probe)

        if (tagEnd < 0) {
            return null
        }

        val bodyStart = tagStart + 1
        val body = text.subSequence(bodyStart, tagEnd)
        val localOffset = (offset - bodyStart).coerceIn(0, body.length)

        return parseCandidates(body)
            .firstOrNull { candidate -> localOffset in candidate.start..candidate.endExclusive }
            ?.value
            ?.takeIf(String::isTaigaSelector)
    }

    private fun parseCandidates(body: CharSequence): List<Candidate> {
        val result = mutableListOf<Candidate>()
        var index = 0

        while (index < body.length && body[index].isWhitespace()) {
            index++
        }

        if (index < body.length && body[index] == '/') {
            index++
        }

        while (index < body.length && body[index].isWhitespace()) {
            index++
        }

        if (index >= body.length || body[index] == '!' || body[index] == '?') {
            return emptyList()
        }

        readName(body, index)?.let { name ->
            result += name
            index = name.endExclusive
        } ?: return emptyList()

        while (index < body.length) {
            while (index < body.length && (body[index].isWhitespace() || body[index] == '/')) {
                index++
            }

            if (index >= body.length) {
                break
            }

            val prefixLength =
                when {
                    body.startsWith("[(", index) -> 2
                    body[index] in charArrayOf('[', '(', '*', '#') -> 1
                    else -> 0
                }

            index += prefixLength

            val attribute = readName(body, index)

            if (attribute == null) {
                index++
                continue
            }

            result += attribute
            index = attribute.endExclusive

            if (body.startsWith(")]", index)) {
                index += 2
            } else if (index < body.length && body[index] in charArrayOf(']', ')')) {
                index++
            }

            while (index < body.length && body[index].isWhitespace()) {
                index++
            }

            if (index < body.length && body[index] == '=') {
                index++
                while (index < body.length && body[index].isWhitespace()) {
                    index++
                }
                index = skipAttributeValue(body, index)
            }
        }

        return result
    }

    private fun readName(
        text: CharSequence,
        start: Int,
    ): Candidate? {
        if (start >= text.length || !text[start].isNameStart()) {
            return null
        }

        var end = start + 1

        while (end < text.length && text[end].isNamePart()) {
            end++
        }

        return Candidate(
            value = text.subSequence(start, end).toString(),
            start = start,
            endExclusive = end,
        )
    }

    private fun skipAttributeValue(
        text: CharSequence,
        start: Int,
    ): Int {
        if (start >= text.length) {
            return start
        }

        val quote = text[start].takeIf { it == '"' || it == '\'' }

        if (quote != null) {
            var index = start + 1

            while (index < text.length && text[index] != quote) {
                index++
            }

            return (index + 1).coerceAtMost(text.length)
        }

        var index = start

        while (index < text.length && !text[index].isWhitespace()) {
            index++
        }

        return index
    }

    private fun Char.isNameStart(): Boolean = isLetter() || this == '_' || this == ':'

    private fun Char.isNamePart(): Boolean = isLetterOrDigit() || this in charArrayOf('_', ':', '-', '.')

    private fun String.isTaigaSelector(): Boolean =
        startsWith("tui-") ||
            (startsWith("tui") && length > 3 && (this[3].isUpperCase() || this[3].isDigit()))

    private fun CharSequence.startsWith(
        value: String,
        startIndex: Int,
    ): Boolean =
        startIndex >= 0 &&
            startIndex + value.length <= length &&
            value.indices.all { index -> this[startIndex + index] == value[index] }

    private data class Candidate(
        val value: String,
        val start: Int,
        val endExclusive: Int,
    )
}
