package org.taigaui.designtokens.documentation

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Performs PSI and versioned documentation resolution outside the EDT.
 * Cancellation and publication remain owned by the hover controller.
 */
internal class TaigaDocumentationHoverResolver(
    private val project: Project,
) {
    fun resolveRequest(
        candidate: TaigaDocumentationHoverCandidate,
        refreshing: Boolean,
        target: TaigaDocumentationRefreshTarget?,
        isCancelled: () -> Boolean,
    ): TaigaQuickDocumentationHoverRequest? =
        ReadAction
            .nonBlocking<TaigaQuickDocumentationHoverRequest?> {
                val committed = if (refreshing) target?.let(candidate::withCommittedTarget) else candidate
                if (project.isDisposed || !candidate.file.isValid) null else committed?.resolveRequest()
            }.withDocumentsCommitted(project)
            .inSmartMode(project)
            .expireWith(project)
            .expireWhen { isCancelled() || candidate.editor.isDisposed }
            .executeSynchronously()

    suspend fun resolveDocumentation(
        request: TaigaQuickDocumentationHoverRequest?,
        refreshing: Boolean,
    ): Pair<TaigaDocsSnapshot?, TaigaResolvedDocumentation?> {
        val snapshot =
            request?.let {
                val service = project.service<TaigaDocsService>()
                if (refreshing) service.snapshotFor(it.sourceFile) else service.cachedSnapshotFor(it.sourceFile)
            }
        val resolved = request?.let { resolveDocumentation(it.documentationRequest, snapshot) }
        if (request != null && snapshot == null) project.service<TaigaDocsService>().warmUp(request.sourceFile)
        return snapshot to resolved
    }
}
