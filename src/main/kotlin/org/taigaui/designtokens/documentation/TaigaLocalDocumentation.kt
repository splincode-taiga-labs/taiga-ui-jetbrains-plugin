package org.taigaui.designtokens.documentation

import java.nio.file.Path

internal data class TaigaDocumentationSource(
    val file: Path,
    val offset: Int,
)

internal data class TaigaPipeParameter(
    val name: String,
    val type: String?,
    val optional: Boolean,
    val description: String? = null,
    val variadic: Boolean = false,
)

internal data class TaigaPipeDocumentation(
    val name: String,
    val parameters: List<TaigaPipeParameter>,
    val resultType: String?,
    val pure: Boolean?,
    val invocation: String? = null,
)

internal data class TaigaInputDefault(
    val name: String,
    val value: String? = null,
    val provider: String? = null,
)

internal data class TaigaLocalDocumentation(
    val source: TaigaDocumentationSource? = null,
    val selector: String? = null,
    val pipe: TaigaPipeDocumentation? = null,
    val inputTypes: Map<String, String> = emptyMap(),
    val defaults: List<TaigaInputDefault> = emptyList(),
)

/** Reads bounded local declaration text; it never evaluates application code. */
internal object TaigaLocalDocumentationParser {
    fun parse(text: String): TaigaLocalDocumentation {
        val declaration = text.take(MAX_DECLARATION_LENGTH)

        return TaigaLocalDocumentation(
            selector = SELECTOR.find(declaration)?.groupValues?.get(1),
            pipe = parsePipe(declaration),
            inputTypes =
                INPUT_TYPE.findAll(declaration).associate { match ->
                    match.groupValues[1] to match.groupValues[2].trim()
                },
            defaults = INPUT_INITIALIZER.findAll(declaration).mapNotNull(::parseDefault).toList(),
        )
    }

    private fun parsePipe(text: String): TaigaPipeDocumentation? {
        val metadata = PIPE_METADATA.find(text)?.groupValues?.get(1)
        val name =
            metadata?.let { NAME.find(it)?.groupValues?.get(1) }
                ?: PIPE_DECLARATION.find(text)?.groupValues?.get(1)
                ?: return null
        val transform = TRANSFORM.find(text)
        val start = transform?.range?.last
        val end = start?.let { closingParenthesis(text, it) }
        val parameters =
            if (start != null && end != null) {
                splitTypeScriptParameters(text.substring(start + 1, end))
                    .mapNotNull(::parseParameter)
                    .map { parameter ->
                        parameter.copy(
                            description =
                                Regex("""@param\s+${Regex.escape(parameter.name)}\s+([^\n*]+)""")
                                    .find(text.substring(0, transform.range.first))
                                    ?.groupValues
                                    ?.get(1)
                                    ?.trim(),
                        )
                    }
            } else {
                emptyList()
            }
        val result =
            end?.let { offset ->
                RETURN_TYPE
                    .find(text.substring(offset + 1))
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
            }

        return TaigaPipeDocumentation(
            name = name,
            parameters = parameters,
            resultType = result,
            // The third Ivy PipeDeclaration argument is standalone, NOT purity.
            pure =
                metadata?.let { attributes ->
                    PURE
                        .find(attributes)
                        ?.groupValues
                        ?.get(1)
                        ?.toBoolean()
                        ?: true.takeUnless { PURE_PROPERTY.containsMatchIn(attributes) }
                },
            invocation = end?.let { RETURN_CALL.find(text.substring(it + 1))?.groupValues?.get(1) },
        )
    }

    private fun parseParameter(text: String): TaigaPipeParameter? {
        val match = PARAMETER.matchEntire(text.trim()) ?: return null

        return TaigaPipeParameter(
            name = match.groupValues[1],
            type = match.groupValues[3].trim().takeIf(String::isNotBlank),
            optional = match.groupValues[2] == "?" || DEFAULT_ASSIGNMENT.containsMatchIn(match.groupValues[3]),
            variadic = text.trim().startsWith("..."),
        )
    }

    private fun parseDefault(match: MatchResult): TaigaInputDefault? {
        val argument = splitTypeScriptParameters(match.groupValues[2]).firstOrNull()?.trim() ?: return null
        val provider = INJECT.find(argument)?.groupValues?.get(1)
        val literal = argument.takeIf { LITERAL.matches(it) }

        return if (provider != null || literal != null) {
            TaigaInputDefault(match.groupValues[1], literal, provider)
        } else {
            null
        }
    }
}

internal val TaigaPipeParameter.presentationName: String
    get() = (if (variadic) "..." else "") + name + if (optional) "?" else ""

internal fun splitTypeScriptParameters(text: String): List<String> {
    val result = mutableListOf<String>()
    var start = 0
    var depth = 0
    var quote: Char? = null
    var escaped = false

    text.forEachIndexed { index, char ->
        if (quote != null) {
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == quote) {
                quote = null
            }
        } else {
            when (char) {
                '\'', '"', '`' -> quote = char
                '(', '[', '{', '<' -> depth++
                ')', ']', '}' -> depth--
                '>' -> if (depth > 0 && text.getOrNull(index - 1) != '=') depth--
                ',' ->
                    if (depth == 0) {
                        result += text.substring(start, index).trim()
                        start = index + 1
                    }
            }
        }
    }

    result += text.substring(start).trim()

    return result.filter(String::isNotBlank)
}

private fun closingParenthesis(
    text: String,
    start: Int,
): Int? {
    var depth = 0

    for (index in start until text.length) {
        when (text[index]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return index
            }
        }
    }

    return null
}

private val SELECTOR =
    Regex("""(?:selector\s*:\s*|ɵɵ(?:Directive|Component)Declaration<[^,]+,\s*)['"]([^'"]+)['"]""")
private val PIPE_METADATA =
    Regex("""(?:@Pipe\s*\(|ɵɵdefinePipe\s*\()\s*\{([^}]+)}""")
private val PIPE_DECLARATION = Regex("""ɵɵPipeDeclaration<[^,]+,\s*['"]([^'"]+)['"]""")
private val NAME = Regex("""\bname\s*:\s*['"]([^'"]+)['"]""")
private val PURE = Regex("""\bpure\s*:\s*(true|false)\b""")
private val PURE_PROPERTY = Regex("""\bpure\s*:""")
private val TRANSFORM = Regex("""\btransform\s*(?:<[^;{]+?>)?\s*\(""")
private val RETURN_TYPE = Regex("""^\s*:\s*([^;{\n]+)""")
private val RETURN_CALL = Regex("""^\s*(?::[^;{]+)?\{\s*return\s+([\w$]+\([^;{}\n]+\))\s*;\s*}""")
private val PARAMETER = Regex("""(?:\.\.\.)?([\w$]+)(\?)?\s*:\s*(.*)""")
private val DEFAULT_ASSIGNMENT = Regex("""(?<![=>])=(?!=|>)""")
private val INPUT_TYPE = Regex("""\b([\w$]+)\s*:\s*(?:\w+\.)?InputSignal<([^;]+)>\s*;""")
private val INPUT_INITIALIZER = Regex("""\b([\w$]+)\s*=\s*input(?:<[^>]+>)?\(([^;\n]+)\)""")
private val INJECT = Regex("""\binject\s*(?:<[^>]+>)?\(\s*([A-Z][A-Z_0-9]+)\s*[,)]""")
private val LITERAL = Regex("""(?:'[^'\\]*'|"[^"\\]*"|true|false|null|undefined|-?\d+(?:\.\d+)?)""")
private const val MAX_DECLARATION_LENGTH = 32_000
