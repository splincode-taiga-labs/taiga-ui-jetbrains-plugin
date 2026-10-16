package org.taigaui.designtokens.documentation

import com.intellij.lang.javascript.evaluation.JSTypeEvaluationLocationProvider
import com.intellij.lang.javascript.psi.JSType
import com.intellij.lang.javascript.psi.ecma6.TypeScriptClass
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import org.angular2.codeInsight.Angular2DeclarationsScope
import org.angular2.codeInsight.attributes.Angular2ApplicableDirectivesProvider
import org.angular2.entities.Angular2AliasedDirectiveProperty
import org.angular2.entities.Angular2ClassBasedEntity
import org.angular2.entities.Angular2Directive
import org.angular2.entities.Angular2DirectiveProperty
import org.angular2.entities.Angular2EntitiesProvider
import org.angular2.entities.source.Angular2SourceDirectiveProperty

internal data class TaigaAngularDocumentation(
    val subjects: List<TaigaDocumentationSubject>,
    val authoritative: Boolean,
)

/** Uses Angular's selector matching, import scope, inherited properties and exposed host aliases. */
internal fun XmlTag.angularDocumentationContext(): TaigaAngularDocumentation =
    JSTypeEvaluationLocationProvider.withTypeEvaluationLocation(containingFile) { captureAngularDocumentation() }

private fun XmlTag.captureAngularDocumentation(): TaigaAngularDocumentation {
    val scope = Angular2DeclarationsScope(this)
    val declared = Angular2ApplicableDirectivesProvider(this).matched
    if (declared.none { it.sourceElement.taigaPackageName() != null }) {
        return TaigaAngularDocumentation(emptyList(), false)
    }
    val matched = declared.filter(scope::contains)
    val subjects =
        matched
            .sortedBy { it.sourceElement.taigaPackageName() == null }
            .asSequence()
            .take(MAX_LOCAL_DIRECTIVES)
            .map { it.documentationSubject(matched.size <= MAX_LOCAL_DIRECTIVES) }
            .toList()
    return TaigaAngularDocumentation(subjects, true)
}

internal fun PsiElement.angularDocumentationSubject(): TaigaDocumentationSubject? =
    JSTypeEvaluationLocationProvider.withTypeEvaluationLocation(containingFile) {
        PsiTreeUtil
            .getParentOfType(this, TypeScriptClass::class.java, false)
            ?.let(Angular2EntitiesProvider::getDirective)
            ?.documentationSubject(true)
    }

private fun Angular2Directive.documentationSubject(complete: Boolean): TaigaDocumentationSubject {
    val source = (this as? Angular2ClassBasedEntity)?.typeScriptClass ?: sourceElement
    val local = source.localDocumentation(getName())
    val properties = inputs.map { it to TaigaApiMemberKind.INPUT } + outputs.map { it to TaigaApiMemberKind.OUTPUT }
    val bounded = properties.take(MAX_LOCAL_API_MEMBERS).map { (property, kind) -> property.documentationMember(kind) }
    val localInputs = bounded.filter { it.kind == TaigaApiMemberKind.INPUT }
    return TaigaDocumentationSubject(
        selector = local.selector,
        publicSymbol = getName(),
        packageName = source.taigaPackageName(),
        localDocumentation =
            local.copy(
                members = bounded,
                angularResolved = true,
                kind = if (isComponent) TaigaDocKind.COMPONENT else TaigaDocKind.DIRECTIVE,
                receiversComplete = complete && properties.size <= MAX_LOCAL_API_MEMBERS,
                inputTypes = localInputs.mapNotNull { member -> member.type?.let { member.name to it } }.toMap(),
                inputValues =
                    localInputs.associate {
                        it.name to
                            it.declaration.localDocumentation.inputValues[it.name]
                                .orEmpty()
                    },
                requiredInputs =
                    localInputs
                        .filter(
                            TaigaLocalApiMember::required,
                        ).map(TaigaLocalApiMember::name)
                        .toSet(),
            ),
    )
}

private fun Angular2DirectiveProperty.documentationMember(kind: TaigaApiMemberKind): TaigaLocalApiMember {
    val original = declaringProperty(kind)
    val source = (original as? Angular2SourceDirectiveProperty)?.sources?.firstOrNull() ?: original.sourceElement
    val owner = PsiTreeUtil.getParentOfType(source, TypeScriptClass::class.java, false)
    val ownerName = owner?.name
    val local = source.inputDocumentation(name, source.localDocumentation(ownerName))
    val transformType = original.transformParameterType
    val accepted = (transformType ?: type)?.getTypeText(JSType.TypeTextFormat.PRESENTABLE)
    val declared = source.inputFieldType(fieldName) ?: local.inputTypes[name]
    val presentation = declared?.let(::inputTypePresentation)
    val effective = accepted?.let(::inputTypePresentation)?.writeType ?: presentation?.writeType ?: declared
    val metadata = source.bindingMetadata()
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
        fieldName = fieldName ?: name,
        required = required,
        expandedType = effective?.let { source.expandedInputType(it) },
        valueType = presentation?.storedType(kind, effective, transformType != null),
        transform = metadata.transform,
        description = metadata.description,
        deprecated = metadata.deprecated,
        replacement = metadata.replacement,
    )
}

private fun Angular2DirectiveProperty.declaringProperty(kind: TaigaApiMemberKind): Angular2DirectiveProperty {
    val alias = this as? Angular2AliasedDirectiveProperty ?: return this
    val bindings = alias.directive.bindings
    val properties = if (kind == TaigaApiMemberKind.INPUT) bindings.inputs else bindings.outputs
    return properties.firstOrNull { it.name == alias.originalName } ?: this
}

private fun TaigaInputTypePresentation.storedType(
    kind: TaigaApiMemberKind,
    accepted: String?,
    transformed: Boolean,
): String? {
    val differentTypes = transformed || readType != writeType
    return readType.takeIf { kind == TaigaApiMemberKind.INPUT && differentTypes && it != accepted }
}

private fun PsiElement.documentationSource(): TaigaDocumentationSource? =
    containingFile?.originalFile?.virtualFile?.path?.let { path ->
        TaigaDocumentationSource(
            java.nio.file.Path
                .of(path),
            textOffset,
        )
    }

private const val MAX_LOCAL_DIRECTIVES = 32
private const val MAX_LOCAL_API_MEMBERS = 128

internal fun List<TaigaDocumentationSubject>.forSelector(selector: String): List<TaigaDocumentationSubject> =
    filter { subject ->
        subject.packageName != null &&
            subject.localDocumentation.selector?.let { value ->
                Regex("(?<![\\w-])${Regex.escape(selector)}(?![\\w-])").containsMatchIn(value)
            } == true
    }
