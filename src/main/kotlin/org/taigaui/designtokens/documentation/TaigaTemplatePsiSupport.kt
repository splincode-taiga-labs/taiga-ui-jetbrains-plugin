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
    if (!PIPE_NAME.matches(name)) return null
    var previous = start - 1
    while (previous >= 0 && text[previous].isWhitespace()) previous--

    return if (previous >= 0 && text[previous] == '|' && (previous == 0 || text[previous - 1] != '|')) {
        TaigaPipeReference(name, start, end)
    } else {
        null
    }
}

internal fun XmlTag.documentationIcons(): List<TaigaDocumentationIcon> =
    attributes.mapNotNull { attribute ->
        val name = attribute.bindingName().removeSurrounding("[", "]")
        val value = attribute.value?.trim().orEmpty().removeSurrounding("'", "'").removeSurrounding("\"", "\"")
        val element = attribute.valueElement
        val offset = element?.text?.indexOf(value) ?: -1

        if (name in ICON_ATTRIBUTES && STATIC_ICON.matches(value) && element != null && offset >= 0) {
            val start = element.textRange.startOffset + offset

            TaigaDocumentationIcon(name, value, start, start + value.length)
        } else {
            null
        }
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
private val PIPE_NAME = Regex("tui[A-Z][A-Za-z0-9_]*")
