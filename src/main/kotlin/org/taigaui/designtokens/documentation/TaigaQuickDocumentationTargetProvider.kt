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
        val subject =
            TaigaTemplateDocumentationResolver.find(file, offset)
                ?: return emptyList()

        return createTarget(file, subject)?.let(::listOf).orEmpty()
    }

    override fun documentationTarget(
        psiFile: PsiFile,
        element: LookupElement,
        offset: Int,
    ): DocumentationTarget? {
        val subject =
            TaigaTemplateDocumentationResolver.find(psiFile, element)
                ?: return null

        return createTarget(psiFile, subject)
    }

    @Suppress("ReturnCount")
    private fun createTarget(
        file: PsiFile,
        subject: TaigaDocumentationSubject,
    ): DocumentationTarget? {
        val sourceFile = file.sourcePath() ?: return null
        val service = file.project.service<TaigaDocsService>()
        val snapshot = service.cachedSnapshotFor(sourceFile)

        if (snapshot == null) {
            service.warmUp(sourceFile)

            return null
        }

        val entity = snapshot.find(subject) ?: return null

        return TaigaQuickDocumentationTarget(entity, subject)
    }
}

private class TaigaQuickDocumentationTarget(
    private val entity: TaigaEntityDoc,
    private val subject: TaigaDocumentationSubject,
) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation =
        TargetPresentation
            .builder(subject.presentationName)
            .presentation()

    override fun computeDocumentation(): DocumentationResult =
        DocumentationResult
            .documentation(TaigaQuickDocumentationRenderer.render(entity, subject))
            .externalUrl(entity.documentationUri.toString())
}

private fun PsiFile.sourcePath(): Path? =
    InjectedLanguageManager
        .getInstance(project)
        .getTopLevelFile(this)
        .virtualFile
        ?.path
        ?.let { path -> runCatching { Path.of(path) }.getOrNull() }

private fun TaigaDocsSnapshot.find(subject: TaigaDocumentationSubject): TaigaEntityDoc? =
    buildList {
        subject.publicSymbol?.let { symbol ->
            addAll(findByPublicSymbol(symbol))
        }
        subject.selector?.let { selector ->
            addAll(findBySelector(selector))
        }
    }.distinctBy(TaigaEntityDoc::sectionId)
        .firstOrNull { entity ->
            entity.packageNames.isEmpty() || subject.packageName in entity.packageNames
        }
