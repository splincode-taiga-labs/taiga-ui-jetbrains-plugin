package org.taigaui.designtokens.documentation

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.injection.InjectedLanguageManager
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
    val localDocumentation: TaigaLocalDocumentation = TaigaLocalDocumentation(),
) {
    val presentationName: String
        get() = publicSymbol ?: selector ?: packageName.orEmpty()
}

internal object TaigaDocumentationResolver {
    fun findRequest(
        file: PsiFile,
        offset: Int,
    ): TaigaDocumentationRequest? {
        val element = file.elementAt(offset) ?: return null
        return findPipeRequest(file, element, offset) ?: if (file.isTaigaTemplateFile()) {
            findTemplateRequest(file, offset)
        } else {
            findCodeRequest(file, offset)
        }
    }

    @Suppress("ReturnCount")
    fun findSubject(
        file: PsiFile,
        element: LookupElement,
    ): TaigaDocumentationSubject? {
        if (!file.isTaigaTemplateFile()) {
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
            val rawName = attribute.bindingName()
            val member = rawName.toMemberBinding()

            if (member != null) return attribute.findMemberRequest(member)

            val selector = rawName.takeIf(::isTaigaSelector) ?: return null
            val subject =
                (attribute.descriptor as? HtmlAttributeSymbolDescriptor)
                    ?.symbol
                    ?.toLocalSubject(selector = selector)
                    ?: selector.fallbackSubject()

            return TaigaDocumentationRequest.Entity(
                subjects = listOf(subject),
                startOffset = nameElement.textRange.startOffset,
                endOffset = nameElement.textRange.endOffset,
                usage = attribute.parent.compactUsage(),
                icons = attribute.parent.documentationIcons(),
                bindings = attribute.parent.documentationBindings(),
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
                ?: tagName.fallbackSubject()

        return TaigaDocumentationRequest.Entity(
            subjects = listOf(subject),
            startOffset = element.textRange.startOffset,
            endOffset = element.textRange.endOffset,
            usage = tag.compactUsage(),
            icons = tag.documentationIcons(),
            bindings = tag.documentationBindings(),
        )
    }

    private fun XmlTag.documentationBindings(): List<TaigaDocumentationBinding> =
        attributes
            .take(MAX_CAPTURED_BINDINGS)
            .mapNotNull { attribute ->
                attribute.documentationBinding()?.let { binding ->
                    val declaration =
                        (attribute.descriptor as? HtmlAttributeSymbolDescriptor)
                            ?.symbol
                            ?.toLocalSubject(selector = null, memberName = binding.name)
                            ?.takeIf { it.packageName != null }
                    binding.copy(declaration = declaration)
                }
            }

    @Suppress("ReturnCount")
    private fun XmlAttribute.findMemberRequest(member: MemberBinding): TaigaDocumentationRequest.Member? {
        val nameElement = nameElement ?: return null
        val tag = parent
        val owners = tag.taigaSubjects()
        if (owners.isEmpty()) return null
        return TaigaDocumentationRequest.Member(
            owners = owners,
            name = member.name,
            kind = member.kind,
            startOffset = nameElement.textRange.startOffset,
            endOffset = nameElement.textRange.endOffset,
            usage = tag.memberUsage(member, owners.firstOrNull()?.selector),
            binding = documentationBinding(),
            declaration =
                (descriptor as? HtmlAttributeSymbolDescriptor)
                    ?.symbol
                    ?.toLocalSubject(
                        selector = null,
                        memberName = member.name.takeIf { member.kind == TaigaApiMemberKind.INPUT },
                    ),
        )
    }

    @Suppress("ReturnCount")
    private fun findPipeRequest(
        file: PsiFile,
        element: PsiElement,
        offset: Int,
    ): TaigaDocumentationRequest.Entity? {
        val reference = findTaigaPipeReference(file.viewProvider.contents, offset) ?: return null
        val manager = InjectedLanguageManager.getInstance(file.project)
        val candidate = manager.findInjectedElementAt(file, offset) ?: element
        val candidateFile = candidate.containingFile
        val templateContext =
            file.isTaigaTemplateFile() ||
                manager.isInjectedFragment(file) ||
                candidateFile.isTaigaTemplateFile() ||
                manager.isInjectedFragment(candidateFile)
        if (!templateContext) return null
        val candidateOffset = if (candidateFile == file) offset else candidate.textOffset
        val declaration =
            candidate.resolveTaigaDeclaration(candidateFile, candidateOffset, incompleteCode = true) ?: return null
        val subject = declaration.toLocalSubject(selector = null) ?: return null
        val pipe = subject.localDocumentation.pipe ?: return null
        return if (reference.name == pipe.name) {
            TaigaDocumentationRequest.Entity(
                subjects = listOf(subject.copy(selector = pipe.name)),
                startOffset = reference.startOffset,
                endOffset = reference.endOffset,
            )
        } else {
            null
        }
    }

    @Suppress("ReturnCount")
    private fun findCodeRequest(
        file: PsiFile,
        offset: Int,
    ): TaigaDocumentationRequest? {
        val element = file.elementAt(offset) ?: return null
        val publicSymbol = element.text.takeIf(::isTaigaPublicSymbol) ?: return null
        val declaration = element.resolveTaigaDeclaration(file, offset)
        val packageName =
            declaration?.taigaPackageName()
                ?: file.taigaImportPackage(publicSymbol)
                ?: return null

        return TaigaDocumentationRequest.Entity(
            subjects =
                listOf(
                    TaigaDocumentationSubject(
                        selector = null,
                        publicSymbol = publicSymbol,
                        packageName = packageName,
                        localDocumentation = declaration?.localDocumentation(publicSymbol) ?: TaigaLocalDocumentation(),
                    ),
                ),
            startOffset = element.textRange.startOffset,
            endOffset = element.textRange.endOffset,
            typeDefinition =
                declaration?.typeDefinition(publicSymbol)
                    ?: file.taigaImportedTypeDefinition(publicSymbol, packageName),
        )
    }
}

private fun XmlTag.taigaSubjects(): List<TaigaDocumentationSubject> =
    buildList {
        name
            .takeIf(::isTaigaSelector)
            ?.let { selector ->
                add(
                    (descriptor as? HtmlElementSymbolDescriptor)
                        ?.symbol
                        ?.toLocalSubject(selector)
                        ?: selector.fallbackSubject(),
                )
                add(selector.fallbackSubject())
            }

        attributes.forEach { attribute ->
            val selector = attribute.name.takeIf(::isTaigaSelector) ?: return@forEach

            add(
                (attribute.descriptor as? HtmlAttributeSymbolDescriptor)
                    ?.symbol
                    ?.toLocalSubject(selector)
                    ?: selector.fallbackSubject(),
            )
            add(selector.fallbackSubject())
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
    memberName: String? = null,
): TaigaDocumentationSubject =
    localContexts()
        .mapNotNull { context ->
            val subject = context.toLocalSubject(selector, requestedSymbol) ?: return@mapNotNull null
            val local = subject.localDocumentation
            val input =
                if (memberName == null) {
                    local
                } else {
                    generateSequence(context) { it.parent }
                        .take(MAX_INPUT_CONTEXT_DEPTH)
                        .map { it.inputDocumentation(memberName, local) }
                        .firstOrNull { it !== local }
                        ?: local
                }
            subject.copy(localDocumentation = input)
        }
        .firstOrNull()
        ?: TaigaDocumentationSubject(
            selector = selector,
            publicSymbol = requestedSymbol,
            packageName = null,
        )

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
            localDocumentation = localDocumentation(requestedSymbol ?: taigaPublicSymbol()),
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
        ?: takeIf { !isTaigaSelector(it) && PLAIN_INPUT_NAME.matches(it) }
            ?.let { MemberBinding(it, TaigaApiMemberKind.INPUT) }

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

private val PLAIN_INPUT_NAME = Regex("[A-Za-z_$][\\w$]*")

private const val MAX_CAPTURED_BINDINGS = 32

private const val MAX_INPUT_CONTEXT_DEPTH = 4
