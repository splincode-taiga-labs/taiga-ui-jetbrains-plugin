package org.taigaui.designtokens.documentation

import com.intellij.model.Pointer
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import java.nio.file.Path
import java.nio.file.Paths

internal class TaigaQuickDocumentationTargetProvider : DocumentationTargetProvider {
    override fun documentationTargets(
        file: PsiFile,
        offset: Int,
    ): List<DocumentationTarget> {
        val selector =
            TaigaTemplateSelectorAtOffset.find(file.viewProvider.contents, offset)
                ?: return emptyList()
        val sourceFile =
            file.virtualFile
                ?.path
                ?.let { path -> runCatching { Paths.get(path) }.getOrNull() }
                ?: return emptyList()

        return listOf(
            TaigaQuickDocumentationTarget(
                project = file.project,
                sourceFile = sourceFile,
                selector = selector,
            ),
        )
    }
}

private class TaigaQuickDocumentationTarget(
    private val project: Project,
    private val sourceFile: Path,
    private val selector: String,
) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation =
        TargetPresentation
            .builder(selector)
            .presentation()

    override fun computeDocumentation(): DocumentationResult =
        DocumentationResult.asyncDocumentation {
            if (project.isDisposed) {
                return@asyncDocumentation null
            }

            val entity =
                project
                    .service<TaigaDocsService>()
                    .snapshotFor(sourceFile)
                    ?.findBySelector(selector)
                    ?.firstOrNull()
                    ?: return@asyncDocumentation null

            DocumentationResult
                .documentation(TaigaQuickDocumentationRenderer.render(entity))
                .externalUrl(entity.documentationUri.toString())
        }
}
