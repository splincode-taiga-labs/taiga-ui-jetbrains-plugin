package org.taigaui.designtokens.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.css.CssRuleset
import org.taigaui.designtokens.diagnostics.PerformanceDiagnostics
import org.taigaui.designtokens.diagnostics.PerformanceMetric
import org.taigaui.designtokens.index.DesignTokenDeclaration
import org.taigaui.designtokens.index.DesignTokenSourceExtractor
import org.taigaui.designtokens.index.DesignTokenSourceFormat
import org.taigaui.designtokens.index.isValidDesignTokenName
import java.nio.file.Path

class PsiDesignTokenSourceExtractor(
    private val project: Project,
) : DesignTokenSourceExtractor {
    override fun extract(sourceFile: Path): List<DesignTokenDeclaration> =
        PerformanceDiagnostics.measure(PerformanceMetric.PSI_EXTRACTION) {
            extractMeasured(sourceFile)
        }

    private fun extractMeasured(sourceFile: Path): List<DesignTokenDeclaration> {
        val normalizedSourceFile = sourceFile.toAbsolutePath().normalize()
        val localFileSystem = LocalFileSystem.getInstance()
        val virtualFile =
            localFileSystem.findFileByNioFile(normalizedSourceFile)
                ?: if (project.isInitialized) {
                    localFileSystem.refreshAndFindFileByNioFile(normalizedSourceFile)
                } else {
                    null
                }
                ?: return emptyList()

        return ReadAction
            .nonBlocking<List<DesignTokenDeclaration>> {
                PsiManager
                    .getInstance(project)
                    .findFile(virtualFile)
                    ?.let { psiFile ->
                        extract(psiFile, normalizedSourceFile)
                            .filter { declaration -> declaration.isGlobalDeclaration() }
                    }.orEmpty()
            }.withDocumentsCommitted(project)
            .expireWith(project)
            .executeSynchronously()
    }

    internal fun extract(
        psiFile: PsiFile,
        sourceFile: Path,
    ): List<DesignTokenDeclaration> {
        val normalizedSourceFile = sourceFile.toAbsolutePath().normalize()
        val declarations = mutableListOf<DesignTokenDeclaration>()
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        val content = psiFile.text

        psiFile.accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    ProgressManager.checkCanceled()

                    if (element is CssDeclaration) {
                        element
                            .toDesignTokenDeclaration(
                                sourceFile = normalizedSourceFile,
                                content = content,
                                line =
                                    document
                                        ?.getLineNumber(element.textOffset)
                                        ?.plus(1)
                                        ?: lineNumber(content, element.textOffset),
                            )?.let(declarations::add)
                    }

                    super.visitElement(element)
                }
            },
        )

        return declarations
    }

    private fun CssDeclaration.toDesignTokenDeclaration(
        sourceFile: Path,
        content: String,
        line: Int,
    ): DesignTokenDeclaration? {
        val tokenName = propertyName
        val rawValue = rawValue()

        return if (tokenName.isValidDesignTokenName() && rawValue.isNotEmpty()) {
            DesignTokenDeclaration(
                name = tokenName,
                value = rawValue,
                sourceFile = sourceFile,
                line = line,
                selectorChain = contextChain(content),
                deprecation = PsiDesignTokenDeprecationExtractor.extract(this, tokenName),
            )
        } else {
            null
        }
    }

    private fun CssDeclaration.rawValue(): String =
        generateSequence(firstChild, PsiElement::getNextSibling)
            .dropWhile { it.text != COLON }
            .drop(1)
            .joinToString(separator = "", transform = PsiElement::getText)
            .trim()

    private fun CssDeclaration.contextChain(content: String): List<String> =
        buildList {
            addAll(cssSelectorChain())
            LessThemeMixinContextFinder.find(content, textOffset)?.let(::add)
        }.distinct()

    private fun CssDeclaration.cssSelectorChain(): List<String> =
        generateSequence(parent, PsiElement::getParent)
            .mapNotNull { element ->
                (element as? CssRuleset)
                    ?.selectorList
                    ?.text
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            }.toList()
            .asReversed()

    private fun DesignTokenDeclaration.isGlobalDeclaration(): Boolean =
        GlobalDesignTokenContext.isGlobal(selectorChain) ||
            (
                selectorChain.isEmpty() &&
                    DesignTokenSourceFormat.from(sourceFile) == DesignTokenSourceFormat.SCSS
            )

    private fun lineNumber(
        content: String,
        offset: Int,
    ): Int = content.take(offset).count { it == '\n' } + 1

    private companion object {
        const val COLON = ":"
    }
}
