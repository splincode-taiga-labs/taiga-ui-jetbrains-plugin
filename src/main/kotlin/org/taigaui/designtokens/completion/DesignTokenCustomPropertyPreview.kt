package org.taigaui.designtokens.completion

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.model.Pointer
import com.intellij.openapi.project.Project
import com.intellij.polySymbols.PolySymbol
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import org.taigaui.designtokens.documentation.DesignTokenHoverPackageSection
import org.taigaui.designtokens.documentation.DesignTokenHoverPopupModel
import org.taigaui.designtokens.documentation.DesignTokenHoverValueRow
import org.taigaui.designtokens.documentation.DesignTokenNavigationTarget
import java.nio.file.Path

internal fun LookupElement.toCustomPropertyPreviewModel(
    tokenName: String,
    project: Project,
): DesignTokenHoverPopupModel? =
    linkedPsiElement()?.toCustomPropertyPreviewModel(tokenName)
        ?: project.toCustomPropertyPreviewModel(tokenName)

private fun Project.toCustomPropertyPreviewModel(tokenName: String): DesignTokenHoverPopupModel? {
    val rows =
        findCustomPropertyDeclarations(tokenName)
            .distinct()
            .sortedWith(
                compareBy(
                    { declaration -> declaration.sourceFile?.toString().orEmpty() },
                    { declaration -> declaration.line ?: Int.MAX_VALUE },
                    ProjectCustomPropertyDeclaration::value,
                ),
            ).map { declaration -> declaration.toHoverValueRow() }

    return rows
        .takeIf(List<*>::isNotEmpty)
        ?.let { valueRows ->
            DesignTokenHoverPopupModel(
                tokenName = tokenName,
                description = null,
                sections =
                    listOf(
                        DesignTokenHoverPackageSection(
                            packageName = "Project custom property",
                            rows = valueRows,
                            chains = emptyList(),
                        ),
                    ),
            )
        }
}

private fun Project.findCustomPropertyDeclarations(tokenName: String): List<ProjectCustomPropertyDeclaration> {
    val declarations = mutableListOf<ProjectCustomPropertyDeclaration>()
    val scope = GlobalSearchScope.projectScope(this)
    val searchWord = tokenName.removePrefix("--")

    PsiSearchHelper
        .getInstance(this)
        .processAllFilesWithWord(
            searchWord,
            scope,
            { file ->
                val extension = file.virtualFile?.extension?.lowercase()

                if (extension in CUSTOM_PROPERTY_SOURCE_EXTENSIONS) {
                    val document = PsiDocumentManager.getInstance(this).getDocument(file)
                    val text = document?.immutableCharSequence ?: file.text
                    val sourceFile =
                        file.virtualFile
                            ?.path
                            ?.let { path -> runCatching { Path.of(path) }.getOrNull() }

                    customPropertyPattern(tokenName)
                        .findAll(text)
                        .mapNotNull { match ->
                            match
                                .groups[1]
                                ?.value
                                ?.trim()
                                ?.takeIf(String::isNotEmpty)
                                ?.let { value ->
                                    ProjectCustomPropertyDeclaration(
                                        value = value,
                                        sourceFile = sourceFile,
                                        line = document?.getLineNumber(match.range.first)?.plus(1),
                                    )
                                }
                        }.forEach(declarations::add)
                }

                true
            },
            true,
        )

    return declarations
}

private fun ProjectCustomPropertyDeclaration.toHoverValueRow(): DesignTokenHoverValueRow {
    val sourceLabel =
        buildString {
            append(sourceFile?.fileName ?: "Project styles")
            line?.let { value ->
                append(':')
                append(value)
            }
        }

    return DesignTokenHoverValueRow(
        platform = sourceLabel,
        resolvedValue = value,
        color = null,
        navigationTarget =
            if (sourceFile != null && line != null) {
                DesignTokenNavigationTarget(sourceFile, line)
            } else {
                null
            },
    )
}

private fun LookupElement.linkedPsiElement(): PsiElement? {
    val lookupObject = getObject()
    val dereferenced =
        (lookupObject as? Pointer<*>)
            ?.dereference()

    return psiElement
        ?: lookupObject.polySymbolPsiElement()
        ?: dereferenced.polySymbolPsiElement()
}

private fun Any?.polySymbolPsiElement(): PsiElement? = (this as? PolySymbol)?.psiContext

private fun PsiElement.toCustomPropertyPreviewModel(tokenName: String): DesignTokenHoverPopupModel? {
    val declaration = findCustomPropertyDeclaration(tokenName) ?: return null
    val sourceFile =
        containingFile
            ?.virtualFile
            ?.path
            ?.let { path -> runCatching { Path.of(path) }.getOrNull() }
    val line =
        containingFile
            ?.let { file -> PsiDocumentManager.getInstance(project).getDocument(file) }
            ?.getLineNumber(declaration.offset)
            ?.plus(1)
    val row =
        ProjectCustomPropertyDeclaration(
            value = declaration.value,
            sourceFile = sourceFile,
            line = line,
        ).toHoverValueRow()

    return DesignTokenHoverPopupModel(
        tokenName = tokenName,
        description = null,
        sections =
            listOf(
                DesignTokenHoverPackageSection(
                    packageName = "Project custom property",
                    rows = listOf(row),
                    chains = emptyList(),
                ),
            ),
    )
}

private fun PsiElement.findCustomPropertyDeclaration(tokenName: String): CustomPropertyDeclaration? {
    val file = containingFile ?: return null
    val document = PsiDocumentManager.getInstance(project).getDocument(file)
    val text = document?.immutableCharSequence ?: file.text
    val sourceOffset = textOffset

    return customPropertyPattern(tokenName)
        .findAll(text)
        .mapNotNull { match ->
            match
                .groups[1]
                ?.value
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { value ->
                    CustomPropertyDeclaration(
                        value = value,
                        offset = match.range.first,
                    )
                }
        }.minByOrNull { declaration ->
            kotlin.math.abs(declaration.offset - sourceOffset)
        }
}

internal fun extractCustomPropertyValue(
    text: String,
    tokenName: String,
): String? {
    val match = customPropertyPattern(tokenName).find(text)

    return match
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
}

private data class ProjectCustomPropertyDeclaration(
    val value: String,
    val sourceFile: Path?,
    val line: Int?,
)

private data class CustomPropertyDeclaration(
    val value: String,
    val offset: Int,
)

private fun customPropertyPattern(tokenName: String): Regex =
    Regex(
        pattern = """(?s)(?:^|[;{])\s*${Regex.escape(tokenName)}\s*:\s*([^;{}]+)""",
    )

private val CUSTOM_PROPERTY_SOURCE_EXTENSIONS = setOf("css", "less", "scss")
