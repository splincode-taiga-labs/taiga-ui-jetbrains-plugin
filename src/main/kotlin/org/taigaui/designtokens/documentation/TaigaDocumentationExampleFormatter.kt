package org.taigaui.designtokens.documentation

import com.intellij.ide.highlighter.HtmlFileType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Formats an isolated HTML PSI copy using WebStorm's own code style.
 * Never creates a physical file, runs a Node process, or modifies the user's template.
 */
internal class TaigaDocumentationExampleFormatter(
    private val project: Project,
) {
    suspend fun format(resolved: TaigaResolvedDocumentation): TaigaResolvedDocumentation {
        val example = resolved.entity.example ?: return resolved
        if (example.language?.lowercase() != "html" || example.code.length > MAX_EXAMPLE_FORMAT_LENGTH) {
            return resolved
        }

        val formatted =
            withContext(Dispatchers.EDT) {
                var result = example.code
                WriteCommandAction.runWriteCommandAction(project) {
                    result = formatInWriteAction(example.code)
                }
                result
            }
        if (formatted == example.code) return resolved

        val entity = resolved.entity.copy(example = example.copy(code = formatted))
        return when (resolved) {
            is TaigaResolvedDocumentation.Entity -> resolved.copy(entity = entity)
            is TaigaResolvedDocumentation.Member -> resolved.copy(entity = entity)
        }
    }

    /** Call only inside a write command on EDT; exposed for real WebStorm formatter tests. */
    internal fun formatInWriteAction(source: String): String {
        if (source.isBlank() || source.length > MAX_EXAMPLE_FORMAT_LENGTH) return source

        return try {
            val copy =
                PsiFileFactory.getInstance(project).createFileFromText(
                    "TaigaExample.component.html",
                    HtmlFileType.INSTANCE,
                    source,
                )
            val candidate = CodeStyleManager.getInstance(project).reformat(copy, true).text

            candidate.takeIf { text ->
                text.isNotBlank() &&
                    text.length <= MAX_FORMATTED_EXAMPLE_LENGTH &&
                    documentationExamplePreservesContent(source, text)
            } ?: source
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (_: Exception) {
            // A documentation preview must remain available even if native formatting fails.
            source
        }
    }
}

/**
 * Only whitespace inside HTML tags (outside quoted values) may change.
 * Text nodes, Angular control-flow/interpolation text, and quoted bindings must stay byte-for-byte equal.
 * If we cannot tokenize a template conservatively, keep its original formatting.
 */
internal fun documentationExamplePreservesContent(
    original: String,
    formatted: String,
): Boolean {
    if (original == formatted) return true
    if (original.contains("<!--") || original.contains("<pre") || original.contains("<textarea")) return false
    return documentationExampleTokens(original)?.let { it == documentationExampleTokens(formatted) } == true
}

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

        val tag = StringBuilder()
        var quote: Char? = null
        var end = -1
        for (position in index until code.length) {
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
        if (end == -1) return null
        tokens.add("tag:" + tag)
        index = end + 1
    }

    return tokens
}

private const val MAX_EXAMPLE_FORMAT_LENGTH = 4_000
private const val MAX_FORMATTED_EXAMPLE_LENGTH = 12_000
