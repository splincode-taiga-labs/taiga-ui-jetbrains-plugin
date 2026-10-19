package org.taigaui.designtokens.documentation

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import java.awt.Dimension
import java.awt.Point
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

@Suppress("ReturnCount")
internal fun EditorMouseEvent.toTaigaDocumentationHoverCandidate(project: Project): TaigaDocumentationHoverCandidate? {
    if (!canStartTaigaDocumentationHover(project)) {
        return null
    }
    val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null
    val text = editor.document.immutableCharSequence
    if (offset !in 0..text.length) return null
    var start = offset
    var end = offset
    while (start > 0 && text[start - 1].isDocumentationNameCharacter()) start--
    while (end < text.length && text[end].isDocumentationNameCharacter()) end++
    if (start == end) return null

    return TaigaDocumentationHoverCandidate(
        file,
        editor,
        Point(mouseEvent.point),
        offset,
        TaigaQuickDocumentationHoverKey(editor, start, end, editor.document.modificationStamp),
    )
}

private fun Char.isDocumentationNameCharacter(): Boolean = isLetterOrDigit() || this == '_' || this == '-'

internal fun TaigaDocumentationHoverCandidate.resolveRequest(): TaigaQuickDocumentationHoverRequest? {
    val request = TaigaDocumentationResolver.findRequest(file, offset)
    val sourceFile = file.sourcePath()

    return if (request != null && sourceFile != null) {
        TaigaQuickDocumentationHoverRequest(
            editor = editor,
            anchor = anchor,
            sourceFile = sourceFile,
            documentationRequest = request,
            modificationStamp = key.modificationStamp,
            actionContext = TaigaDocumentationActionContext(file.project, editor.document),
            explicit = explicit,
        )
    } else {
        null
    }
}

internal data class TaigaDocumentationHoverCandidate(
    val file: PsiFile,
    val editor: Editor,
    val anchor: Point,
    val offset: Int,
    val key: TaigaQuickDocumentationHoverKey,
    val explicit: Boolean = false,
)

/** Resolve the tracked refresh target against the committed document after indexing/typing settles. */
internal fun TaigaDocumentationHoverCandidate.withCommittedTarget(
    target: TaigaDocumentationRefreshTarget,
): TaigaDocumentationHoverCandidate? =
    target.offset()?.let { position ->
        copy(
            offset = position,
            key = key.copy(modificationStamp = editor.document.modificationStamp),
        )
    }

private fun EditorMouseEvent.canStartTaigaDocumentationHover(project: Project): Boolean {
    val projectState =
        area == EditorMouseEventArea.EDITING_AREA &&
            editor.project == project &&
            !project.isDisposed &&
            !editor.isDisposed
    val editorState =
        !editor.selectionModel.hasSelection() &&
            LookupManager.getInstance(project).activeLookup == null
    val settingEnabled =
        EditorSettingsExternalizable
            .getInstance()
            .isShowQuickDocOnMouseOverElement

    return projectState && editorState && settingEnabled
}

internal fun TaigaQuickDocumentationHoverRequest.isStillCurrent(project: Project): Boolean {
    val projectState =
        editor.project == project &&
            !project.isDisposed &&
            !editor.isDisposed
    val documentState =
        (explicit || !editor.selectionModel.hasSelection()) &&
            editor.document.modificationStamp == modificationStamp
    val uiState =
        LookupManager.getInstance(project).activeLookup == null &&
            (
                explicit ||
                    EditorSettingsExternalizable
                        .getInstance()
                        .isShowQuickDocOnMouseOverElement
            )

    return projectState && documentState && uiState && actionContext.isCurrent()
}

internal fun TaigaQuickDocumentationHoverRequest.completePresentation(
    panel: TaigaQuickDocumentationPopupPanel,
    refreshing: Boolean,
) {
    if (refreshing) {
        panel.showRefreshProgress(editor.project?.let(DumbService::isDumb) == true)
    } else if (!actionContext.isCurrent()) {
        panel.invalidateContext()
    }
}

internal val TaigaQuickDocumentationHoverRequest.key: TaigaQuickDocumentationHoverKey
    get() =
        TaigaQuickDocumentationHoverKey(
            editor = editor,
            startOffset = documentationRequest.startOffset,
            endOffset = documentationRequest.endOffset,
            modificationStamp = modificationStamp,
        )

internal data class TaigaQuickDocumentationHoverRequest(
    val editor: Editor,
    val anchor: Point,
    val sourceFile: Path,
    val documentationRequest: TaigaDocumentationRequest,
    val modificationStamp: Long,
    val actionContext: TaigaDocumentationActionContext,
    val explicit: Boolean = false,
) {
    fun documentationTargetScreenBounds() =
        editor.documentationTargetScreenBounds(documentationRequest.startOffset, documentationRequest.endOffset)

    fun popupPlacement(size: Dimension): TaigaHoverPopupPlacement {
        val target = documentationTargetScreenBounds()
        if (explicit) {
            return TaigaHoverPopupPlacement(Point(target.x, target.y + target.height), TaigaHoverPointerSide.NONE)
        }
        return taigaHoverPopupPlacementForScreen(target, size)
    }
}

internal data class TaigaQuickDocumentationHoverKey(
    val editor: Editor,
    val startOffset: Int,
    val endOffset: Int,
    val modificationStamp: Long,
)

internal val TaigaDocumentationRequest.shouldHighlight: Boolean
    get() =
        this is TaigaDocumentationRequest.Member ||
            (this is TaigaDocumentationRequest.Entity && subjects.any { subject -> subject.selector != null })

internal val HOVER_HIDE_GRACE_PERIOD = 750.milliseconds
