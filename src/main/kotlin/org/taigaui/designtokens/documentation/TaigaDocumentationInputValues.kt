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
    val file = containingFile ?: return emptyList()
    val declaration =
        Regex("\\b${Regex.escape(type)}\\b")
            .findAll(text.take(MAX_ALIAS_FILE_TEXT))
            .take(MAX_ALIAS_REFERENCES)
            .mapNotNull { match ->
                val offset = textRange.startOffset + match.range.first
                file.findElementAt(offset)?.resolveTaigaDeclaration(file, offset)
            }.firstOrNull { element -> element.typeDefinition(type) != null }
            ?: return emptyList()
    val definition = declaration.typeDefinition(type) ?: return emptyList()
    return declaration.localInputValues(definition, visited + type)
}

internal fun TaigaResolvedDocumentation.Member.localValues(): List<String> {
    if (kind != TaigaApiMemberKind.INPUT) return emptyList()
    val local = declaration?.localDocumentation?.takeIf { property.name in it.inputTypes } ?: subject.localDocumentation
    return local.inputValues[property.name]
        ?: local.inputTypes[property.name]?.let(::finiteStringValues).orEmpty()
}

private const val MAX_ALIAS_DEPTH = 4
private const val MAX_ALIAS_REFERENCES = 16
private const val MAX_ALIAS_FILE_TEXT = 32_000
private val TYPE_NAME = Regex("[A-Za-z_$][\\w$]*")
private val STRING_VALUE = Regex("""(['"])([\w .@/-]+)\1""")
