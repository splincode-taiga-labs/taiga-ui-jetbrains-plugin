package org.taigaui.designtokens.documentation

import com.intellij.lang.html.HtmlCompatibleFile
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag

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
