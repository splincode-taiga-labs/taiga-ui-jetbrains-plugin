package org.taigaui.designtokens.documentation

import com.intellij.lang.html.HtmlCompatibleFile
import com.intellij.psi.PsiFile

internal fun PsiFile.isTaigaTemplateFile(): Boolean =
    this is HtmlCompatibleFile ||
        viewProvider.allFiles.any { candidate -> candidate is HtmlCompatibleFile }

internal fun String.fallbackSubject(): TaigaDocumentationSubject =
    TaigaDocumentationSubject(
        selector = this,
        publicSymbol = null,
        packageName = null,
    )
