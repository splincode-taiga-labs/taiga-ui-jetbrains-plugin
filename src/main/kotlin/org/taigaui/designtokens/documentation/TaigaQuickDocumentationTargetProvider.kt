package org.taigaui.designtokens.documentation

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.model.Pointer
import com.intellij.openapi.components.service
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.documentation.LookupElementDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import java.nio.file.Path

internal class TaigaQuickDocumentationTargetProvider :
    DocumentationTargetProvider,
    LookupElementDocumentationTargetProvider {
    override fun documentationTargets(
        file: PsiFile,
        offset: Int,
    ): List<DocumentationTarget> {
        val request = TaigaDocumentationResolver.findRequest(file, offset) ?: return emptyList()

        return createTarget(file, request)?.let(::listOf).orEmpty()
    }

    override fun documentationTarget(
        psiFile: PsiFile,
        element: LookupElement,
        offset: Int,
    ): DocumentationTarget? {
        val subject = TaigaDocumentationResolver.findSubject(psiFile, element) ?: return null
        val request =
            TaigaDocumentationRequest.Entity(
                subjects = listOf(subject),
                startOffset = offset,
                endOffset = offset,
            )

        return createTarget(psiFile, request)
    }

    @Suppress("ReturnCount")
    private fun createTarget(
        file: PsiFile,
        request: TaigaDocumentationRequest,
    ): DocumentationTarget? {
        val sourceFile = file.sourcePath() ?: return null
        val service = file.project.service<TaigaDocsService>()
        val snapshot = service.cachedSnapshotFor(sourceFile)

        if (snapshot == null) {
            service.warmUp(sourceFile)
        }

        val resolved = resolveDocumentation(request, snapshot) ?: return null

        return TaigaQuickDocumentationTarget(resolved)
    }
}

private class TaigaQuickDocumentationTarget(
    private val resolved: TaigaResolvedDocumentation,
) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation =
        TargetPresentation
            .builder(resolved.presentationName)
            .presentation()

    override fun computeDocumentation(): DocumentationResult =
        DocumentationResult
            .documentation(TaigaQuickDocumentationRenderer.render(resolved))
            .externalUrl(resolved.documentationUri.toString())
}

internal fun PsiFile.sourcePath(): Path? =
    InjectedLanguageManager
        .getInstance(project)
        .getTopLevelFile(this)
        .virtualFile
        ?.path
        ?.let { path -> runCatching { Path.of(path) }.getOrNull() }
