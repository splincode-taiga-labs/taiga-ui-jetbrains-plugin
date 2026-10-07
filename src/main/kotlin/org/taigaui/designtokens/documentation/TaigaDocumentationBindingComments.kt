package org.taigaui.designtokens.documentation

import com.intellij.lang.javascript.psi.ecma6.TypeScriptClass
import com.intellij.psi.PsiElement

internal data class TaigaDocumentationBindingComments(
    val description: String?,
    val deprecated: String?,
    val replacement: String?,
)

internal fun PsiElement.bindingComments(): TaigaDocumentationBindingComments {
    val comment = associatedBindingComment()
    val deprecated = DEPRECATED.find(comment)?.groupValues?.get(1)?.trim()
    return TaigaDocumentationBindingComments(
        description = comment.bindingDescription(),
        deprecated = deprecated,
        replacement = deprecated?.let { REPLACEMENT.find(it)?.groupValues?.get(1) },
    )
}

private fun PsiElement.associatedBindingComment(): String =
    generateSequence(this) { it.parent?.takeUnless { parent -> parent is TypeScriptClass } }
        .take(MAX_COMMENT_PARENTS)
        .firstNotNullOfOrNull { element ->
            val text = element.text.orEmpty()
            DOC_COMMENT.find(text)?.takeIf { text.substring(0, it.range.first).isBlank() }?.value
                ?: generateSequence(element.prevSibling) { it.prevSibling }
                    .dropWhile { it.text.isNullOrBlank() }
                    .firstOrNull()?.text?.trim()?.let { DOC_COMMENT.matchEntire(it)?.value }
        }.orEmpty()

private fun String.bindingDescription(): String? =
    removePrefix("/**").removeSuffix("*/").lineSequence()
        .map { it.trim().removePrefix("*").trim() }
        .takeWhile { !it.startsWith('@') }
        .filter(String::isNotBlank)
        .joinToString(" ")
        .takeIf(String::isNotBlank)

private val DOC_COMMENT = Regex("""/\*\*[\s\S]*?\*/""")
private val DEPRECATED = Regex("""@deprecated(?:[ \t]+([^\n*]+))?""")
private val REPLACEMENT = Regex("""(?i)\buse\s+(?:\{@link\s+|[`'"])?([A-Za-z_$][\w$]*)(?:}|[`'"])?\s+instead\b""")
private const val MAX_COMMENT_PARENTS = 3
