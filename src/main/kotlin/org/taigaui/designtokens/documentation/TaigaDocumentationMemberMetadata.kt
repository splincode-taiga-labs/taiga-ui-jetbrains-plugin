package org.taigaui.designtokens.documentation

import com.intellij.lang.javascript.documentation.JSDocumentationUtils
import com.intellij.psi.PsiElement

internal data class TaigaDocumentationMemberMetadata(
    val description: String?,
    val deprecated: String?,
    val replacement: String?,
    val transform: String?,
)

internal fun PsiElement.bindingMetadata(): TaigaDocumentationMemberMetadata {
    val comment = bindingDocComment()
    val deprecated =
        DEPRECATED
            .find(comment)
            ?.groupValues
            ?.get(1)
            ?.trim()
    return TaigaDocumentationMemberMetadata(
        description = comment.bindingDescription(),
        deprecated = deprecated,
        replacement = deprecated?.let { REPLACEMENT.find(it)?.groupValues?.get(1) },
        transform = TRANSFORM.find(text.take(MAX_MEMBER_TEXT))?.groupValues?.get(1),
    )
}

private fun PsiElement.bindingDocComment(): String =
    JSDocumentationUtils
        .findDocComment(this)
        ?.text
        ?.take(MAX_MEMBER_TEXT)
        .orEmpty()

private fun String.bindingDescription(): String? =
    removePrefix("/**")
        .removeSuffix("*/")
        .lineSequence()
        .map { it.trim().removePrefix("*").trim() }
        .takeWhile { !it.startsWith('@') }
        .filter(String::isNotBlank)
        .joinToString(" ")
        .takeIf(String::isNotBlank)

private val DEPRECATED = Regex("""@deprecated\s+([^\n*]+)""")
private val REPLACEMENT = Regex("""(?i)\buse\s+(?:\{@link\s+|[`'"])?([A-Za-z_$][\w$]*)(?:}|[`'"])?\s+instead\b""")
private val TRANSFORM = Regex("""\btransform\s*:\s*([\w$.]+)""")
private const val MAX_MEMBER_TEXT = 8_000
