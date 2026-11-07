package org.taigaui.designtokens.documentation

import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Presentation-only HTML formatter. Uses project-local Prettier when available,
 * otherwise wraps attributes using a safe, deterministic fallback.
 */
internal class TaigaDocumentationExampleFormatter(
    @Suppress("UNUSED_PARAMETER") project: Project,
    private val prettier: TaigaDocumentationExamplePrettier = TaigaDocumentationExamplePrettier(),
) {
    suspend fun format(
        resolved: TaigaResolvedDocumentation,
        sourceFile: Path,
    ): TaigaResolvedDocumentation =
        withContext(Dispatchers.IO) {
            val example = resolved.entity.example
            val nextExample =
                example?.takeIf { it.language?.lowercase() == "html" }
                    ?.let { it.copy(code = prettier.format(it.code, sourceFile)) }
                    ?: example
            val nextUsage =
                resolved.usage?.takeIf { it.trimStart().startsWith('<') }
                    ?.let { prettier.format(it, sourceFile) }
                    ?: resolved.usage
            if (example == nextExample && resolved.usage == nextUsage) {
                resolved
            } else {
                val entity = resolved.entity.copy(example = nextExample)
                when (resolved) {
                    is TaigaResolvedDocumentation.Entity -> resolved.copy(entity = entity, usage = nextUsage)
                    is TaigaResolvedDocumentation.Member -> resolved.copy(entity = entity, usage = nextUsage)
                }
            }
        }

    /** Synchronous fallback for initial preview before optional background Prettier finishes. */
    internal fun formatInWriteAction(source: String): String = formattedDocumentationExample(source)
}

/**
 * Only whitespace inside HTML tags (outside quoted values) may change.
 * Text nodes, Angular control-flow/interpolation text, and quoted bindings must stay byte-for-byte equal.
 * If we cannot tokenize a template conservatively, keep its original formatting.
 */
internal fun documentationExamplePreservesContent(
    original: String,
    formatted: String,
): Boolean =
    original == formatted ||
        (
            !original.contains("<!--") &&
                !original.contains("<pre") &&
                !original.contains("<textarea") &&
                documentationExampleTokens(original)?.let { it == documentationExampleTokens(formatted) } == true
        )

private fun documentationExampleTokens(code: String): List<String>? {
    val tokens = mutableListOf<String>()
    var index = 0

    while (index < code.length) {
        if (code[index] != '<') {
            val end = code.indexOf('<', index).takeIf { it >= 0 } ?: code.length
            tokens.add("text:" + code.substring(index, end))
            index = end
            continue
        }

        val tag = documentationExampleTag(code, index) ?: return null
        tokens.add(tag.first)
        index = tag.second + 1
    }

    return tokens
}

private fun documentationExampleTag(
    code: String,
    start: Int,
): Pair<String, Int>? {
    val tag = StringBuilder()
    var quote: Char? = null
    var end = -1
    for (position in start until code.length) {
        val char = code[position]
        when {
            quote != null -> {
                tag.append(char)
                if (char == quote) quote = null
            }
            char == '"' || char == '\'' -> {
                quote = char
                tag.append(char)
            }
            char == '>' -> {
                tag.append(char)
                end = position
                break
            }
            !char.isWhitespace() -> tag.append(char)
        }
    }
    return if (end < 0) null else ("tag:" + tag) to end
}

private const val MAX_EXAMPLE_FORMAT_LENGTH = 4_000
private const val MAX_FORMATTED_EXAMPLE_LENGTH = 12_000
