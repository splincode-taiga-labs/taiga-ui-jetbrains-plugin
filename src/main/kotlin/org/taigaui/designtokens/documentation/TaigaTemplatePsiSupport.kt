package org.taigaui.designtokens.documentation

import com.intellij.lang.html.HtmlCompatibleFile
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag

internal data class TaigaDocumentationIcon(
    val attribute: String,
    val name: String,
    val startOffset: Int,
    val endOffset: Int,
)

internal data class TaigaPipeReference(
    val name: String,
    val startOffset: Int,
    val endOffset: Int,
)

internal fun findTaigaPipeReference(
    text: CharSequence,
    offset: Int,
): TaigaPipeReference? {
    if (offset !in 0..text.length) return null
    var start = offset
    var end = offset
    while (start > 0 && text[start - 1].isJavaIdentifierPart()) start--
    while (end < text.length && text[end].isJavaIdentifierPart()) end++
    val name = text.subSequence(start, end).toString()
    var previous = start - 1
    while (previous >= 0 && text[previous].isWhitespace()) previous--
    val hasPipeOperator = previous >= 0 && text[previous] == '|'
    val isBooleanOperator = previous > 0 && text[previous - 1] == '|'

    return if (PIPE_NAME.matches(name) && hasPipeOperator && !isBooleanOperator) {
        TaigaPipeReference(name, start, end)
    } else {
        null
    }
}

internal fun XmlTag.documentationIcons(): List<TaigaDocumentationIcon> =
    attributes.mapNotNull { it.documentationIcon() }

@Suppress("ReturnCount")
private fun XmlAttribute.documentationIcon(): TaigaDocumentationIcon? {
    val binding = documentationBinding() ?: return null
    val literal = binding.literal ?: return null
    if (binding.name !in ICON_ATTRIBUTES) return null
    if (literal.isNotEmpty() && !STATIC_ICON.matches(literal)) return null
    val raw = binding.text.substring(binding.valueStart, binding.valueEnd)
    val relative =
        if (binding.expression) {
            val match = ICON_LITERAL.matchEntire(raw.trim()) ?: return null
            if (match.groupValues[2] != literal) return null
            raw.length - raw.trimStart().length + 1
        } else {
            if (raw != literal) return null
            0
        }
    val start = binding.startOffset + binding.valueStart + relative
    return TaigaDocumentationIcon(binding.name, literal, start, start + literal.length)
}

internal fun PsiFile.isTaigaTemplateFile(): Boolean =
    this is HtmlCompatibleFile ||
        viewProvider.allFiles.any { candidate -> candidate is HtmlCompatibleFile }

internal fun String.fallbackSubject(): TaigaDocumentationSubject =
    TaigaDocumentationSubject(
        selector = this,
        publicSymbol = null,
        packageName = null,
    )

internal fun XmlAttribute.bindingName(): String =
    text
        .substringBefore('=')
        .trim()
        .takeIf(String::isNotBlank)
        ?: name

internal fun XmlTag.compactUsage(): String =
    text
        .replace(TEMPLATE_WHITESPACE, " ")
        .trim()
        .take(MAX_TEMPLATE_USAGE_LENGTH)

private val TEMPLATE_WHITESPACE = Regex("\\s+")
private const val MAX_TEMPLATE_USAGE_LENGTH = 260
private val ICON_ATTRIBUTES = setOf("icon", "iconStart", "iconEnd", "badge")
private val STATIC_ICON = Regex("@tui\\.[A-Za-z0-9_.-]+")
private val ICON_LITERAL = Regex("""(['"])([\w .@/-]*)\1""")
private val PIPE_NAME = Regex("tui[A-Z][A-Za-z0-9_]*")
