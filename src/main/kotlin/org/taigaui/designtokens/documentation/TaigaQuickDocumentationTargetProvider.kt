package org.taigaui.designtokens.documentation

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.model.Pointer
import com.intellij.model.Symbol
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LookupElementDocumentationTargetProvider
import com.intellij.platform.backend.documentation.SymbolDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.polySymbols.PolySymbol
import com.intellij.psi.PsiFile
import java.nio.file.Path

internal class TaigaQuickDocumentationTargetProvider :
    SymbolDocumentationTargetProvider,
    LookupElementDocumentationTargetProvider {
    override fun documentationTarget(
        project: Project,
        symbol: Symbol,
    ): DocumentationTarget? {
        val polySymbol = symbol as? PolySymbol ?: return null
        val subject = TaigaTemplateDocumentationResolver.find(polySymbol) ?: return null
        val declarationFile = TaigaTemplateDocumentationResolver.sourcePath(polySymbol) ?: return null
        val service = project.service<TaigaDocsService>()
        val snapshot =
            service.cachedSnapshotForResolvedPackage(
                sourceFile = declarationFile,
                packageName = subject.packageName,
            )

        if (snapshot == null) {
            service.warmUp(declarationFile)
            return null
        }

        return snapshot.createTarget(subject)
    }

    override fun documentationTarget(
        psiFile: PsiFile,
        element: LookupElement,
        offset: Int,
    ): DocumentationTarget? {
        val subject = TaigaTemplateDocumentationResolver.find(psiFile, element) ?: return null
        val sourceFile = psiFile.sourcePath() ?: return null
        val service = psiFile.project.service<TaigaDocsService>()
        val snapshot = service.cachedSnapshotFor(sourceFile)

        if (snapshot == null) {
            service.warmUp(sourceFile)
            return null
        }

        return snapshot.createTarget(subject)
    }

    private fun TaigaDocsSnapshot.createTarget(subject: TaigaDocumentationSubject): DocumentationTarget? {
        val entity = find(subject) ?: return null

        return TaigaQuickDocumentationTarget(
            entity = entity,
            subject = subject.completedFrom(entity),
        )
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

internal fun PsiFile.sourcePath(): Path? =
    InjectedLanguageManager
        .getInstance(project)
        .getTopLevelFile(this)
        .virtualFile
        ?.path
        ?.let { path -> runCatching { Path.of(path) }.getOrNull() }

internal fun TaigaDocsSnapshot.find(subject: TaigaDocumentationSubject): TaigaEntityDoc? =
    buildList {
        subject.publicSymbol?.let { symbol ->
            addAll(findByPublicSymbol(symbol))
        }
        subject.selector?.let { selector ->
            addAll(findBySelector(selector))
        }
    }.distinctBy(TaigaEntityDoc::sectionId)
        .firstOrNull { entity ->
            subject.packageName == null ||
                entity.packageNames.isEmpty() ||
                subject.packageName in entity.packageNames
        }

internal fun TaigaDocumentationSubject.completedFrom(entity: TaigaEntityDoc): TaigaDocumentationSubject =
    copy(
        publicSymbol = publicSymbol ?: entity.publicSymbols.singleOrNull(),
        packageName = packageName ?: entity.packageNames.singleOrNull(),
    )
