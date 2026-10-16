package org.taigaui.designtokens.documentation

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.model.Pointer
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
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
        val selector =
            TaigaTemplateSelectorAtOffset.find(file.viewProvider.contents, offset)
                ?: return emptyList()

        return listOfNotNull(createTarget(file, selector))
    }

    override fun documentationTarget(
        psiFile: PsiFile,
        element: LookupElement,
        offset: Int,
    ): DocumentationTarget? {
        val key =
            element.lookupString.takeIf(TaigaTemplateSelectorAtOffset::isTaigaDocumentationKey)
                ?: return null

        return createTarget(psiFile, key)
    }

    private fun createTarget(
        file: PsiFile,
        key: String,
    ): DocumentationTarget? {
        val sourceFile =
            InjectedLanguageManager
                .getInstance(file.project)
                .getTopLevelFile(file)
                .virtualFile
                ?.path
                ?.let { path -> runCatching { Path.of(path) }.getOrNull() }
                ?: return null

        return TaigaQuickDocumentationTarget(
            project = file.project,
            sourceFile = sourceFile,
            key = key,
        )
    }
}

private class TaigaQuickDocumentationTarget(
    private val project: Project,
    private val sourceFile: Path,
    private val key: String,
) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation =
        TargetPresentation
            .builder(key)
            .presentation()

    override fun computeDocumentation(): DocumentationResult =
        DocumentationResult.asyncDocumentation {
            if (project.isDisposed) {
                return@asyncDocumentation null
            }

            val snapshot =
                project
                    .service<TaigaDocsService>()
                    .snapshotFor(sourceFile)
                    ?: return@asyncDocumentation null
            val entity =
                snapshot.findBySelector(key).firstOrNull()
                    ?: snapshot.findByPublicSymbol(key).firstOrNull()
                    ?: return@asyncDocumentation null

            DocumentationResult
                .documentation(TaigaQuickDocumentationRenderer.render(entity))
                .externalUrl(entity.documentationUri.toString())
        }
}
