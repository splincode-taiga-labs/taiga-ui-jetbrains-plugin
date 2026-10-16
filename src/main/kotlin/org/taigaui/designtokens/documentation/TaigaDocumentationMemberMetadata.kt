package org.taigaui.designtokens.documentation

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace

internal data class TaigaDocumentationMemberMetadata(
    val description: String?,
    val deprecated: String?,
    val replacement: String?,
    val transform: String?,
)

internal fun PsiElement.bindingMetadata(): TaigaDocumentationMemberMetadata {
    val comment = bindingDocComment()
    val deprecated = DEPRECATED.find(comment)?.groupValues?.get(1)?.trim()
    return TaigaDocumentationMemberMetadata(
        description = comment.bindingDescription(),
        deprecated = deprecated,
        replacement = deprecated?.let { REPLACEMENT.find(it)?.groupValues?.get(1) },
        transform = TRANSFORM.find(text.take(MAX_MEMBER_TEXT))?.groupValues?.get(1),
    )
}

private fun PsiElement.bindingDocComment(): String {
    val declaration = text.take(MAX_MEMBER_TEXT)
    val embedded = DOC_COMMENT.find(declaration)?.takeIf { declaration.substring(0, it.range.first).isBlank() }?.value
    if (embedded != null) return embedded
    var sibling = prevSibling
    while (sibling is PsiWhiteSpace) sibling = sibling.prevSibling
    // JavaScript JSDoc is its own PSI element, rather than a platform PsiComment.
    return sibling?.text?.take(MAX_MEMBER_TEXT)?.takeIf { it.startsWith("/**") }.orEmpty()
}

private fun String.bindingDescription(): String? =
    removePrefix("/**")
        .removeSuffix("*/")
        .lineSequence()
        .map { it.trim().removePrefix("*").trim() }
        .takeWhile { !it.startsWith('@') }
        .filter(String::isNotBlank)
        .joinToString(" ")
        .takeIf(String::isNotBlank)

private val DOC_COMMENT = Regex("""/\*\*[\s\S]*?\*/""")
private val DEPRECATED = Regex("""@deprecated\s+([^\n*]+)""")
private val REPLACEMENT = Regex("""(?i)\buse\s+(?:\{@link\s+|[`'"])?([A-Za-z_$][\w$]*)(?:}|[`'"])?\s+instead\b""")
private val TRANSFORM = Regex("""\btransform\s*:\s*([\w$.]+)""")
private const val MAX_MEMBER_TEXT = 8_000
