package org.taigaui.designtokens.documentation

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.html.HtmlCompatibleFile
import com.intellij.polySymbols.PolySymbol
import com.intellij.polySymbols.completion.PolySymbolCodeCompletionItem
import com.intellij.polySymbols.html.attributes.HtmlAttributeSymbolDescriptor
import com.intellij.polySymbols.html.elements.HtmlElementSymbolDescriptor
import com.intellij.polySymbols.utils.unwrapMatchedSymbols
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlTokenType

internal data class TaigaDocumentationSubject(
    val selector: String?,
    val publicSymbol: String?,
    val packageName: String,
) {
    val presentationName: String
        get() = publicSymbol ?: selector ?: packageName
}

internal object TaigaTemplateDocumentationResolver {
    fun find(
        file: PsiFile,
        offset: Int,
    ): TaigaDocumentationSubject? {
        if (file !is HtmlCompatibleFile || file.textLength == 0 || offset !in 0..file.textLength) {
            return null
        }

        val element =
            file.findElementAt(offset.coerceAtMost(file.textLength - 1))
                ?: offset.takeIf { it > 0 }?.let { file.findElementAt(it - 1) }
                ?: return null

        if (element.node.elementType !in NAME_TOKENS) {
            return null
        }

        val name = element.text.takeIf(::isTaigaSelector) ?: return null
        val symbol =
            PsiTreeUtil.getParentOfType(element, XmlAttribute::class.java, false)
                ?.descriptor
                ?.let { descriptor -> (descriptor as? HtmlAttributeSymbolDescriptor)?.symbol }
                ?: PsiTreeUtil
                    .getParentOfType(element, XmlTag::class.java, false)
                    ?.descriptor
                    ?.let { descriptor -> (descriptor as? HtmlElementSymbolDescriptor)?.symbol }
                ?: return null

        return symbol.toLocalSubject(selector = name)
    }

    fun find(
        file: PsiFile,
        element: LookupElement,
    ): TaigaDocumentationSubject? {
        if (file !is HtmlCompatibleFile) {
            return null
        }

        val lookupString = element.lookupString
        val selector = lookupString.takeIf(::isTaigaSelector)
        val requestedSymbol = lookupString.takeIf(::isTaigaPublicSymbol)

        if (selector == null && requestedSymbol == null) {
            return null
        }

        val symbol = PolySymbolCodeCompletionItem.getPolySymbol(element)

        return symbol?.toLocalSubject(selector, requestedSymbol)
            ?: PolySymbolCodeCompletionItem
                .getPsiElement(element)
                ?.toLocalSubject(selector, requestedSymbol)
    }

    private fun PolySymbol.toLocalSubject(
        selector: String?,
        requestedSymbol: String? = null,
    ): TaigaDocumentationSubject? =
        unwrapMatchedSymbols()
            .mapNotNull { symbol -> symbol.psiContext }
            .mapNotNull { context -> context.toLocalSubject(selector, requestedSymbol) }
            .firstOrNull()

    private fun PsiElement.toLocalSubject(
        selector: String?,
        requestedSymbol: String? = null,
    ): TaigaDocumentationSubject? {
        val packageName = taigaPackageName() ?: return null
        val publicSymbol = requestedSymbol ?: taigaPublicSymbol()

        return TaigaDocumentationSubject(
            selector = selector,
            publicSymbol = publicSymbol,
            packageName = packageName,
        )
    }

    private fun PsiElement.taigaPublicSymbol(): String? =
        generateSequence(this as PsiElement?) { element -> element.parent }
            .filterIsInstance<PsiNamedElement>()
            .mapNotNull { named -> named.name }
            .firstOrNull(::isTaigaPublicSymbol)

    private fun PsiElement.taigaPackageName(): String? {
        val path =
            containingFile
                ?.originalFile
                ?.virtualFile
                ?.path
                ?.replace('\\', '/')
                ?: return null
        val match = TAIGA_PACKAGE_PATH.find(path) ?: return null

        return "@taiga-ui/" + match.groupValues[1]
    }

    private fun isTaigaPublicSymbol(value: String): Boolean =
        value.startsWith("Tui") &&
            value.length > 3 &&
            value[3].isUpperCase()

    private fun isTaigaSelector(value: String): Boolean =
        value.startsWith("tui-") ||
            (value.startsWith("tui") && value.length > 3 && (value[3].isUpperCase() || value[3].isDigit()))

    private val NAME_TOKENS = setOf(XmlTokenType.XML_NAME, XmlTokenType.XML_TAG_NAME)
    private val TAIGA_PACKAGE_PATH = Regex("""(?:^|/)@taiga-ui/([^/]+)(?:/|$)""")
}
