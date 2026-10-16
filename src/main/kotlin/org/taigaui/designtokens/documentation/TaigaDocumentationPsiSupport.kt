package org.taigaui.designtokens.documentation

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference

internal fun PsiElement.candidateReferences(
    file: PsiFile,
    offset: Int,
): List<PsiReference> =
    buildList {
        add(file.findReferenceAt(offset))
        add(reference)
        add(parent?.reference)
        addAll(references)
        addAll(parent?.references.orEmpty())
    }.filterNotNull()

internal fun PsiElement.resolveTaigaDeclaration(
    file: PsiFile,
    offset: Int,
): PsiElement? {
    val resolved =
        candidateReferences(file, offset)
            .mapNotNull(PsiReference::resolve)

    return resolved
        .asSequence()
        .flatMap { element ->
            sequenceOf(
                element,
                element.navigationElement,
                element.originalElement,
            )
        }.distinct()
        .firstOrNull { element -> element.taigaPackageName() != null }
}

internal fun PsiFile.taigaImportPackage(symbol: String): String? =
    TAIGA_IMPORT
        .findAll(text)
        .firstOrNull { match ->
            match.groupValues[1]
                .split(',')
                .any { imported -> imported.substringBefore(" as ").trim() == symbol }
        }?.groupValues
        ?.getOrNull(2)

internal fun PsiElement.taigaPackageName(): String? =
    containingFile
        ?.originalFile
        ?.virtualFile
        ?.path
        ?.replace('\\', '/')
        ?.let(TAIGA_PACKAGE_PATH::find)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { packageName -> "@taiga-ui/$packageName" }

internal fun PsiElement.typeDefinition(symbol: String): String? =
    ancestors(TYPE_DEFINITION_PARENT_LIMIT)
        .map { element -> element.text.take(MAX_DECLARATION_TEXT) }
        .mapNotNull { text ->
            Regex(
                """\btype\s+${Regex.escape(symbol)}(?:<[^>]+>)?\s*=\s*(.+?);""",
                RegexOption.DOT_MATCHES_ALL,
            ).find(text)
                ?.groupValues
                ?.get(1)
        }.map { definition -> definition.replace(WHITESPACE, " ").trim() }
        .firstOrNull()
        ?.take(MAX_TYPE_DEFINITION)

private fun PsiElement.ancestors(limit: Int): Sequence<PsiElement> =
    sequence {
        var current: PsiElement? = this@ancestors
        var remaining = limit

        while (current != null && remaining > 0) {
            yield(current)
            current = current.parent
            remaining--
        }
    }

private val TAIGA_IMPORT =
    Regex("""import\s*\{([^}]*)}\s*from\s*['"](@taiga-ui/[^'"]+)['"]""")
private val TAIGA_PACKAGE_PATH = Regex("""(?:^|/)node_modules/@taiga-ui/([^/]+)(?:/|$)""")
private val WHITESPACE = Regex("\\s+")
private const val TYPE_DEFINITION_PARENT_LIMIT = 6
private const val MAX_DECLARATION_TEXT = 8_000
private const val MAX_TYPE_DEFINITION = 800
