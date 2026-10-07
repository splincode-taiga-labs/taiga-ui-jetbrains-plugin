package org.taigaui.designtokens.documentation

import com.intellij.psi.PsiElement

/** Only complete finite string unions from installed declarations authorize an edit. */
internal fun finiteStringValues(type: String): List<String> {
    val parts = type.trim().split('|').map(String::trim)
    val values = parts.map { STRING_VALUE.matchEntire(it)?.groupValues?.get(2) ?: return emptyList() }
    return values.distinct()
}

@Suppress("ReturnCount")
internal fun PsiElement.localInputValues(
    type: String,
    visited: Set<String> = emptySet(),
): List<String> {
    finiteStringValues(type).takeIf(List<String>::isNotEmpty)?.let { return it }
    if ('|' in type) {
        val parts = type.split('|').map { localInputValues(it.trim(), visited) }
        return if (parts.any(List<String>::isEmpty)) emptyList() else parts.flatten().distinct()
    }
    if (visited.size >= MAX_ALIAS_DEPTH || type in visited || !TYPE_NAME.matches(type)) return emptyList()
    val declaration = resolveInputAlias(type) ?: return emptyList()
    val definition = declaration.typeDefinition(type) ?: return emptyList()
    return declaration.localInputValues(definition, visited + type)
}

private fun PsiElement.resolveInputAlias(type: String): PsiElement? {
    val file = containingFile ?: return null
    return Regex("\\b${Regex.escape(type)}\\b")
        .findAll(text.take(MAX_ALIAS_FILE_TEXT))
        .take(MAX_ALIAS_REFERENCES)
        .mapNotNull { match ->
            val offset = textRange.startOffset + match.range.first
            file.findElementAt(offset)?.candidateReferences(file, offset)
                ?.asSequence()
                ?.flatMap { it.resolutionCandidates(false) }
                ?.flatMap { sequenceOf(it, it.navigationElement, it.originalElement) }
                ?.distinct()
                ?.firstOrNull { it.typeDefinition(type) != null }
        }.firstOrNull { element -> element.typeDefinition(type) != null }
}

/** Input symbols can expose an aliased or pre-signal field instead of the public binding name. */
internal fun PsiElement.inputDocumentation(
    name: String,
    local: TaigaLocalDocumentation,
): TaigaLocalDocumentation {
    val field =
        text
            .trim()
            .takeIf { it.length <= MAX_INPUT_FIELD_TEXT }
            ?.let(INPUT_FIELD::matchEntire)
            ?: return local
    val originalName = field.groupValues[1]
    val declaredType = field.groupValues[2].trim()
    val type =
        inputTypePresentation(declaredType).writeType
    return local.copy(
        inputTypes = local.inputTypes + (name to type),
        inputValues = local.inputValues + (name to localInputValues(type)),
        defaults =
            local.defaults +
                local.defaults
                    .filter { it.name == originalName && originalName != name }
                    .map { it.copy(name = name) },
        requiredInputs =
            if (originalName in local.requiredInputs) local.requiredInputs + name else local.requiredInputs,
    )
}

internal data class TaigaInputTypePresentation(
    val readType: String,
    val writeType: String,
)

internal fun PsiElement.inputFieldType(fieldName: String? = null): String? {
    val signature = text.trim().take(MAX_INPUT_FIELD_TEXT)
    return INPUT_FIELD
        .matchEntire(signature)
        ?.groupValues
        ?.get(2)
        ?.trim()
        ?: fieldName?.let { name ->
            Regex("\\b${Regex.escape(name)}[!?]?\\s*:\\s*([^;=]+)")
                .find(signature)
                ?.groupValues
                ?.get(1)
                ?.trim()
        }
}

/** The second InputSignalWithTransform argument is what a template may pass. */
@Suppress("ReturnCount")
internal fun inputTypePresentation(type: String): TaigaInputTypePresentation {
    val match = SIGNAL_TYPE.matchEntire(type.trim()) ?: return TaigaInputTypePresentation(type, type)
    val arguments = splitTypeScriptParameters(match.groupValues[2])
    val read = arguments.firstOrNull() ?: return TaigaInputTypePresentation(type, type)
    val write = if (match.groupValues[1] == "InputSignalWithTransform") arguments.getOrNull(1) ?: type else read
    return TaigaInputTypePresentation(read, write)
}

@Suppress("ReturnCount")
internal fun PsiElement.expandedInputType(
    type: String,
    visited: Set<String> = emptySet(),
): String? {
    if (visited.size >= MAX_ALIAS_DEPTH || type in visited) return null
    val values = localInputValues(type)
    if (values.isNotEmpty()) return values.joinToString(" | ") { "'$it'" }.takeIf { it != type }
    if (!TYPE_NAME.matches(type)) return null
    val declaration = resolveInputAlias(type) ?: return null
    val definition = declaration.typeDefinition(type) ?: return null
    return declaration.expandedInputType(definition, visited + type) ?: definition.take(MAX_EXPANDED_TYPE)
}

@Suppress("ReturnCount")
internal fun TaigaResolvedDocumentation.Member.localValues(): List<String> {
    if (kind != TaigaApiMemberKind.INPUT) return emptyList()
    if (!subject.localDocumentation.receiversComplete) return emptyList()
    if (receivers.isNotEmpty()) {
        val sets = receivers.map { it.localValues() }
        if (sets.any(List<String>::isEmpty)) return emptyList()
        return sets.reduce { left, right -> left.filter { it in right } }
    }
    val local = declaration?.localDocumentation?.takeIf { property.name in it.inputTypes } ?: subject.localDocumentation
    return local.inputValues[property.name]
        ?: local.inputTypes[property.name]?.let(::finiteStringValues).orEmpty()
}

private const val MAX_ALIAS_DEPTH = 4
private const val MAX_ALIAS_REFERENCES = 16
private const val MAX_ALIAS_FILE_TEXT = 32_000
private val TYPE_NAME = Regex("[A-Za-z_$][\\w$]*")
private val STRING_VALUE = Regex("""(['"])([\w .@/-]+)\1""")

private const val MAX_INPUT_FIELD_TEXT = 8_000
private val INPUT_FIELD =
    Regex("""(?:(?:public|protected|private|readonly|declare|override|abstract)\s+)*([\w$]+)[!?]?\s*:\s*([^;=]+);?""")
private val SIGNAL_TYPE = Regex("""(?:[\w$]+\.)?(InputSignal|InputSignalWithTransform)<([\s\S]+)>""")
private const val MAX_EXPANDED_TYPE = 800
