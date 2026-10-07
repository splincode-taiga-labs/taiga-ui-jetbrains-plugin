package org.taigaui.designtokens.documentation

import com.intellij.lang.javascript.psi.JSType
import com.intellij.lang.javascript.psi.ecma6.TypeScriptClass
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import org.angular2.codeInsight.Angular2DeclarationsScope
import org.angular2.codeInsight.attributes.Angular2ApplicableDirectivesProvider
import org.angular2.entities.Angular2Directive
import org.angular2.entities.Angular2DirectiveProperty

/** Uses Angular's selector matching, import scope, inherited properties and exposed host aliases. */
internal fun XmlTag.angularDocumentationSubjects(): List<TaigaDocumentationSubject> {
    val scope = Angular2DeclarationsScope(this)
    val matched = Angular2ApplicableDirectivesProvider(this, false, scope).matched
    if (matched.none { it.sourceElement.taigaPackageName() != null }) return emptyList()
    return matched.sortedBy { it.sourceElement.taigaPackageName() == null }
        .asSequence()
        .take(MAX_LOCAL_DIRECTIVES)
        .map { it.documentationSubject(matched.size <= MAX_LOCAL_DIRECTIVES) }
        .toList()
}

private fun Angular2Directive.documentationSubject(complete: Boolean): TaigaDocumentationSubject {
    val local = sourceElement.localDocumentation(getName())
    val properties = inputs.map { it to TaigaApiMemberKind.INPUT } + outputs.map { it to TaigaApiMemberKind.OUTPUT }
    val bounded = properties.take(MAX_LOCAL_API_MEMBERS).map { (property, kind) -> property.documentationMember(kind) }
    val localInputs = bounded.filter { it.kind == TaigaApiMemberKind.INPUT }
    return TaigaDocumentationSubject(
        selector = local.selector,
        publicSymbol = getName(),
        packageName = sourceElement.taigaPackageName(),
        localDocumentation =
            local.copy(
                members = bounded,
                angularResolved = true,
                kind = if (isComponent) TaigaDocKind.COMPONENT else TaigaDocKind.DIRECTIVE,
                receiversComplete = complete && properties.size <= MAX_LOCAL_API_MEMBERS,
                inputTypes = localInputs.mapNotNull { member -> member.type?.let { member.name to it } }.toMap(),
                inputValues = localInputs.associate { it.name to it.declaration.localDocumentation.inputValues[it.name].orEmpty() },
                requiredInputs = localInputs.filter(TaigaLocalApiMember::required).map(TaigaLocalApiMember::name).toSet(),
            ),
    )
}

private fun Angular2DirectiveProperty.documentationMember(kind: TaigaApiMemberKind): TaigaLocalApiMember {
    val source = sourceElement
    val owner = PsiTreeUtil.getParentOfType(source, TypeScriptClass::class.java, false)
    val ownerName = owner?.name
    val local = source.inputDocumentation(name, source.localDocumentation(ownerName))
    val accepted = (transformParameterType ?: type)?.getTypeText(JSType.TypeTextFormat.PRESENTABLE)
    val declared = source.inputFieldType() ?: local.inputTypes[name]
    val presentation = declared?.let(::inputTypePresentation)
    val effective = accepted?.let(::inputTypePresentation)?.writeType ?: presentation?.writeType ?: declared
    val comment = source.bindingDocComment()
    val deprecated = DEPRECATED.find(comment)?.groupValues?.get(1)?.trim()
    val projected =
        local.copy(
            source = source.documentationSource() ?: local.source,
            inputTypes = local.inputTypes + listOfNotNull(effective?.let { name to it }).toMap(),
            inputValues = local.inputValues + (name to effective?.let { source.localInputValues(it) }.orEmpty()),
            requiredInputs = if (required) local.requiredInputs + name else local.requiredInputs,
        )
    return TaigaLocalApiMember(
        name = name,
        kind = kind,
        type = effective,
        declaration = TaigaDocumentationSubject(null, ownerName, source.taigaPackageName(), projected),
        fieldName = fieldName,
        required = required,
        expandedType = effective?.let { source.expandedInputType(it) },
        valueType = presentation?.readType?.takeIf { it != effective },
        transform = TRANSFORM.find(source.text)?.groupValues?.get(1),
        description = comment.bindingDescription(),
        deprecated = deprecated,
        replacement = deprecated?.let { REPLACEMENT.find(it)?.groupValues?.get(1) },
    )
}

private fun PsiElement.bindingDocComment(): String {
    val embedded = DOC_COMMENT.find(text)?.takeIf { text.substring(0, it.range.first).isBlank() }?.value
    if (embedded != null) return embedded
    var sibling = prevSibling
    while (sibling is PsiWhiteSpace) sibling = sibling.prevSibling
    return (sibling as? PsiComment)?.text?.takeIf { it.startsWith("/**") }.orEmpty()
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

private fun PsiElement.documentationSource(): TaigaDocumentationSource? =
    containingFile?.originalFile?.virtualFile?.path?.let { path ->
        TaigaDocumentationSource(java.nio.file.Path.of(path), textOffset)
    }

private val DOC_COMMENT = Regex("""/\*\*[\s\S]*?\*/""")
private val DEPRECATED = Regex("""@deprecated\s+([^\n*]+)""")
private val REPLACEMENT = Regex("""(?i)\buse\s+(?:\{@link\s+|[`'"])?([A-Za-z_$][\w$]*)(?:}|[`'"])?\s+instead\b""")
private val TRANSFORM = Regex("""\btransform\s*:\s*([\w$.]+)""")
private const val MAX_LOCAL_DIRECTIVES = 32
private const val MAX_LOCAL_API_MEMBERS = 128
