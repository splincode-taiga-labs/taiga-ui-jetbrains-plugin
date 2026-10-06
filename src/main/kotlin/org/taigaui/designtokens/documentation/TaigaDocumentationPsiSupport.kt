package org.taigaui.designtokens.documentation

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import java.nio.file.Path

internal fun PsiElement.localDocumentation(symbol: String?): TaigaLocalDocumentation {
    val declaration =
        ancestors(TYPE_DEFINITION_PARENT_LIMIT)
            .firstOrNull { element ->
                val name = symbol?.let(Regex::escape) ?: "Tui\\w+"
                Regex("\\bclass\\s+$name\\b").containsMatchIn(element.text.take(MAX_LOCAL_DECLARATION_TEXT))
            }
            ?: this
    val file = declaration.containingFile?.originalFile?.virtualFile
    val path = file?.takeIf { it.extension in DECLARATION_EXTENSIONS }?.path
    val source = path?.let { value -> runCatching { Path.of(value) }.getOrNull() }

    return TaigaLocalDocumentationParser.parse(declaration.text).copy(
        source = source?.let { file -> TaigaDocumentationSource(file, declaration.textOffset) },
    )
}

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
            .asSequence()
            .flatMap(PsiReference::resolutionCandidates)
            .toList()

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

private fun PsiReference.resolutionCandidates(): Sequence<PsiElement> =
    when (this) {
        is PsiPolyVariantReference ->
            multiResolve(false)
                .asSequence()
                .mapNotNull { result -> result.element }

        else -> listOfNotNull(resolve()).asSequence()
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
        .mapNotNull { text -> text.extractTypeDefinition(symbol) }
        .firstOrNull()

internal fun PsiFile.taigaImportedTypeDefinition(
    symbol: String,
    packageName: String,
): String? {
    val relativePath = "node_modules/$packageName/index.d.ts"
    val declarationFile =
        generateSequence(originalFile.virtualFile?.parent) { directory -> directory.parent }
            .mapNotNull { directory -> directory.findFileByRelativePath(relativePath) }
            .firstOrNull()
            ?.let { virtualFile -> PsiManager.getInstance(project).findFile(virtualFile) }
            ?: return null

    return declarationFile.text
        .take(MAX_DECLARATION_TEXT)
        .extractTypeDefinition(symbol)
}

private fun String.extractTypeDefinition(symbol: String): String? =
    Regex(
        """\btype\s+${Regex.escape(symbol)}(?:<[^>]+>)?\s*=\s*(.+?);""",
        RegexOption.DOT_MATCHES_ALL,
    ).find(this)
        ?.groupValues
        ?.get(1)
        ?.replace(WHITESPACE, " ")
        ?.trim()
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
private const val MAX_LOCAL_DECLARATION_TEXT = 32_000
private val DECLARATION_EXTENSIONS = setOf("ts", "js", "mjs", "mts", "cts", "cjs")
