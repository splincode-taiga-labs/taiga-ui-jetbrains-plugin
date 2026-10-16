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

internal data class TaigaDocumentationSubject(
    val selector: String?,
    val publicSymbol: String?,
    val packageName: String?,
) {
    val presentationName: String
        get() = publicSymbol ?: selector ?: packageName.orEmpty()
}

internal object TaigaDocumentationResolver {
    fun findRequest(
        file: PsiFile,
        offset: Int,
    ): TaigaDocumentationRequest? =
        if (file is HtmlCompatibleFile) {
            findTemplateRequest(file, offset)
        } else {
            findCodeRequest(file, offset)
        }

    @Suppress("ReturnCount")
    fun findSubject(
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

        return PolySymbolCodeCompletionItem
            .getPsiElement(element)
            ?.toLocalSubject(selector, requestedSymbol)
    }

    @Suppress("ReturnCount")
    private fun findTemplateRequest(
        file: PsiFile,
        offset: Int,
    ): TaigaDocumentationRequest? {
        val element = file.elementAt(offset) ?: return null
        val attribute = PsiTreeUtil.getParentOfType(element, XmlAttribute::class.java, false)

        if (attribute != null && attribute.nameElement?.textRange?.containsOffset(offset) == true) {
            val nameElement = attribute.nameElement ?: return null
            val rawName = nameElement.text
            val member = rawName.toMemberBinding()

            if (member != null) {
                val tag = attribute.parent as? XmlTag ?: return null
                val owners = tag.taigaSubjects()

                if (owners.isEmpty()) {
                    return null
                }

                return TaigaDocumentationRequest.Member(
                    owners = owners,
                    name = member.name,
                    kind = member.kind,
                    startOffset = nameElement.textRange.startOffset,
                    endOffset = nameElement.textRange.endOffset,
                    usage = tag.memberUsage(member, owners.firstOrNull()?.selector),
                )
            }

            val selector = rawName.takeIf(::isTaigaSelector)
            val subject =
                selector
                    ?.let { name ->
                        (attribute.descriptor as? HtmlAttributeSymbolDescriptor)
                            ?.symbol
                            ?.toLocalSubject(selector = name)
                    }
                    ?: return null

            return TaigaDocumentationRequest.Entity(
                subjects = listOf(subject),
                startOffset = nameElement.textRange.startOffset,
                endOffset = nameElement.textRange.endOffset,
            )
        }

        val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java, false) ?: return null
        val tagName = tag.name.takeIf(::isTaigaSelector) ?: return null

        if (element.text != tagName) {
            return null
        }

        val subject =
            (tag.descriptor as? HtmlElementSymbolDescriptor)
                ?.symbol
                ?.toLocalSubject(selector = tagName)
                ?: return null

        return TaigaDocumentationRequest.Entity(
            subjects = listOf(subject),
            startOffset = element.textRange.startOffset,
            endOffset = element.textRange.endOffset,
        )
    }

    @Suppress("ReturnCount")
    private fun findCodeRequest(
        file: PsiFile,
        offset: Int,
    ): TaigaDocumentationRequest? {
        val element = file.elementAt(offset) ?: return null
        val publicSymbol = element.text.takeIf(::isTaigaPublicSymbol) ?: return null
        val declaration =
            element
                .candidateReferences(file, offset)
                .asSequence()
                .mapNotNull(PsiReference::resolve)
                .firstOrNull { resolved -> resolved.taigaPackageName() != null }
                ?: return null
        val packageName = declaration.taigaPackageName() ?: return null

        return TaigaDocumentationRequest.Entity(
            subjects =
                listOf(
                    TaigaDocumentationSubject(
                        selector = null,
                        publicSymbol = publicSymbol,
                        packageName = packageName,
                    ),
                ),
            startOffset = element.textRange.startOffset,
            endOffset = element.textRange.endOffset,
            typeDefinition = declaration.typeDefinition(publicSymbol),
        )
    }
}

private fun XmlTag.taigaSubjects(): List<TaigaDocumentationSubject> =
    buildList {
        name
            .takeIf(::isTaigaSelector)
            ?.let { selector ->
                (descriptor as? HtmlElementSymbolDescriptor)
                    ?.symbol
                    ?.toLocalSubject(selector)
                    ?.let(::add)
            }

        attributes.forEach { attribute ->
            val selector = attribute.name.takeIf(::isTaigaSelector) ?: return@forEach

            (attribute.descriptor as? HtmlAttributeSymbolDescriptor)
                ?.symbol
                ?.toLocalSubject(selector)
                ?.let(::add)
        }
    }.distinctBy { subject ->
        listOf(subject.selector, subject.publicSymbol, subject.packageName)
    }

private fun XmlTag.memberUsage(
    member: MemberBinding,
    ownerSelector: String?,
): String {
    val selector =
        ownerSelector
            ?.takeUnless { value -> value == name }
            ?.let { value -> " $value" }
            .orEmpty()
    val binding =
        when (member.kind) {
            TaigaApiMemberKind.INPUT -> "[${member.name}]=\"value\""
            TaigaApiMemberKind.OUTPUT -> "(${member.name})=\"handler(\$event)\""
        }

    return "<$name$selector $binding>...</$name>"
}

private fun PolySymbol.toLocalSubject(
    selector: String?,
    requestedSymbol: String? = null,
): TaigaDocumentationSubject? =
    localContexts()
        .mapNotNull { context -> context.toLocalSubject(selector, requestedSymbol) }
        .firstOrNull()

private fun PolySymbol.localContexts(): List<PsiElement> =
    unwrapMatchedSymbols()
        .mapNotNull { symbol -> symbol.psiContext }
        .toList()
        .ifEmpty { listOfNotNull(psiContext) }

private fun PsiElement.toLocalSubject(
    selector: String?,
    requestedSymbol: String? = null,
): TaigaDocumentationSubject? =
    taigaPackageName()?.let { packageName ->
        TaigaDocumentationSubject(
            selector = selector,
            publicSymbol = requestedSymbol ?: taigaPublicSymbol(),
            packageName = packageName,
        )
    }

private fun PsiElement.taigaPublicSymbol(): String? =
    generateSequence(this) { element: PsiElement -> element.parent }
        .filterIsInstance<PsiNamedElement>()
        .mapNotNull(PsiNamedElement::getName)
        .firstOrNull(::isTaigaPublicSymbol)

private fun PsiFile.elementAt(offset: Int): PsiElement? =
    takeIf { file -> file.textLength > 0 && offset in 0..file.textLength }
        ?.let { file ->
            file.findElementAt(offset.coerceAtMost(file.textLength - 1))
                ?: offset
                    .takeIf { value -> value > 0 }
                    ?.let { value -> file.findElementAt(value - 1) }
        }

private fun String.toMemberBinding(): MemberBinding? =
    MEMBER_BINDING_PATTERNS.firstNotNullOfOrNull { pattern -> pattern.parse(this) }

private fun isTaigaPublicSymbol(value: String): Boolean =
    value.startsWith("Tui") &&
        value.length > 3 &&
        value[3].isUpperCase()

private fun isTaigaSelector(value: String): Boolean =
    value.startsWith("tui-") ||
        (value.startsWith("tui") && value.length > 3 && (value[3].isUpperCase() || value[3].isDigit()))

private data class MemberBinding(
    val name: String,
    val kind: TaigaApiMemberKind,
)

private data class MemberBindingPattern(
    val prefix: String,
    val suffix: String,
    val kind: TaigaApiMemberKind,
) {
    fun parse(value: String): MemberBinding? =
        value
            .takeIf { candidate ->
                candidate.startsWith(prefix) &&
                    candidate.endsWith(suffix) &&
                    candidate.length > prefix.length + suffix.length
            }?.removePrefix(prefix)
            ?.removeSuffix(suffix)
            ?.let { name -> MemberBinding(name, kind) }
}

private val MEMBER_BINDING_PATTERNS =
    listOf(
        MemberBindingPattern("[(", ")]", TaigaApiMemberKind.INPUT),
        MemberBindingPattern("[", "]", TaigaApiMemberKind.INPUT),
        MemberBindingPattern("(", ")", TaigaApiMemberKind.OUTPUT),
        MemberBindingPattern("bind-", "", TaigaApiMemberKind.INPUT),
        MemberBindingPattern("on-", "", TaigaApiMemberKind.OUTPUT),
    )
