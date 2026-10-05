package org.taigaui.designtokens.documentation

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorMouseHoverPopupManager
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.editor.impl.EditorMouseHoverPopupControl
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class TaigaQuickDocumentationHoverPopupListener :
    EditorMouseListener,
    EditorMouseMotionListener {
    override fun mousePressed(event: EditorMouseEvent) {
        event.dismissTaigaQuickDocumentationHover()
    }

    override fun mouseExited(event: EditorMouseEvent) {
        event.dismissTaigaQuickDocumentationHover()
    }

    override fun mouseDragged(event: EditorMouseEvent) {
        event.dismissTaigaQuickDocumentationHover()
    }

    override fun mouseMoved(event: EditorMouseEvent) {
        val editor = event.editor
        val project = editor.project ?: return

        project.service<TaigaQuickDocumentationHoverController>().mouseMoved(event)
    }
}

private fun EditorMouseEvent.dismissTaigaQuickDocumentationHover() {
    val project = editor.project ?: return

    project.service<TaigaQuickDocumentationHoverController>().dismissHover(editor)
}

@Service(Service.Level.PROJECT)
internal class TaigaQuickDocumentationHoverController(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    private var activeKey: TaigaQuickDocumentationHoverKey? = null
    private var shownKey: TaigaQuickDocumentationHoverKey? = null
    private var hoverJob: Job? = null
    private var nativeHoverSuppressedEditor: Editor? = null

    fun mouseMoved(event: EditorMouseEvent) {
        val request = event.toTaigaQuickDocumentationHoverRequest(project)
        val requestKey = request?.key

        when {
            requestKey == null -> dismissHover(event.editor)
            requestKey == shownKey -> {
                activeKey = requestKey
                hoverJob?.cancel()
                hoverJob = null
                restoreNativeHover()
            }
            requestKey != activeKey -> {
                activeKey = requestKey
                shownKey = null
                hoverJob?.cancel()
                suppressNativeHover(request.editor)
                warmDocumentation(request)
                hoverJob = scheduleHover(request)
            }
            else -> suppressNativeHover(request.editor)
        }
    }

    fun dismissHover(editor: Editor? = null) {
        if (editor == null || editor.project == project) {
            activeKey = null
            shownKey = null
            hoverJob?.cancel()
            hoverJob = null
            restoreNativeHover()
        }
    }

    private fun warmDocumentation(request: TaigaQuickDocumentationHoverRequest) {
        val service = project.service<TaigaDocsService>()

        if (service.cachedSnapshotFor(request.sourceFile) == null) {
            service.warmUp(request.sourceFile)
        }
    }

    private fun scheduleHover(request: TaigaQuickDocumentationHoverRequest): Job =
        coroutineScope.launch(Dispatchers.EDT + CoroutineName("Taiga UI quick documentation hover")) {
            delay(TAIGA_HOVER_SHOW_DELAY)

            if (activeKey != request.key || !request.isStillCurrent(project)) {
                clearIfCurrent(request.key)
                return@launch
            }

            hoverJob = null
            restoreNativeHover()

            if (JBPopupFactory.getInstance().isPopupActive) {
                clearIfCurrent(request.key)
                return@launch
            }

            shownKey = request.key
            EditorMouseHoverPopupManager.getInstance().showInfoTooltip(request.event)
        }

    private fun clearIfCurrent(key: TaigaQuickDocumentationHoverKey) {
        if (activeKey == key) {
            activeKey = null
            shownKey = null
            hoverJob = null
            restoreNativeHover()
        }
    }

    private fun suppressNativeHover(editor: Editor) {
        if (nativeHoverSuppressedEditor === editor) {
            return
        }

        restoreNativeHover()
        EditorMouseHoverPopupControl.disablePopups(editor)
        nativeHoverSuppressedEditor = editor
    }

    private fun restoreNativeHover() {
        val editor = nativeHoverSuppressedEditor ?: return

        nativeHoverSuppressedEditor = null

        if (!editor.isDisposed) {
            EditorMouseHoverPopupControl.enablePopups(editor)
        }
    }
}

private fun EditorMouseEvent.toTaigaQuickDocumentationHoverRequest(
    project: Project,
): TaigaQuickDocumentationHoverRequest? {
    if (
        area != EditorMouseEventArea.EDITING_AREA ||
        editor.project != project ||
        project.isDisposed ||
        editor.isDisposed ||
        editor.selectionModel.hasSelection() ||
        LookupManager.getInstance(project).activeLookup != null ||
        !EditorSettingsExternalizable.getInstance().isShowQuickDocOnMouseOverElement
    ) {
        return null
    }

    val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null
    val match =
        ReadAction.compute<TaigaDocumentationMatch?, RuntimeException> {
            TaigaTemplateDocumentationResolver.findMatch(file, offset)
        } ?: return null
    val sourceFile = file.sourcePath() ?: return null

    return TaigaQuickDocumentationHoverRequest(
        editor = editor,
        event = this,
        sourceFile = sourceFile,
        startOffset = match.startOffset,
        endOffset = match.endOffset,
        modificationStamp = editor.document.modificationStamp,
    )
}

private fun TaigaQuickDocumentationHoverRequest.isStillCurrent(project: Project): Boolean =
    editor.project == project &&
        !project.isDisposed &&
        !editor.isDisposed &&
        !editor.selectionModel.hasSelection() &&
        editor.document.modificationStamp == modificationStamp &&
        LookupManager.getInstance(project).activeLookup == null &&
        EditorSettingsExternalizable.getInstance().isShowQuickDocOnMouseOverElement

private val TaigaQuickDocumentationHoverRequest.key: TaigaQuickDocumentationHoverKey
    get() =
        TaigaQuickDocumentationHoverKey(
            editor = editor,
            startOffset = startOffset,
            endOffset = endOffset,
            modificationStamp = modificationStamp,
        )

private data class TaigaQuickDocumentationHoverRequest(
    val editor: Editor,
    val event: EditorMouseEvent,
    val sourceFile: java.nio.file.Path,
    val startOffset: Int,
    val endOffset: Int,
    val modificationStamp: Long,
)

private data class TaigaQuickDocumentationHoverKey(
    val editor: Editor,
    val startOffset: Int,
    val endOffset: Int,
    val modificationStamp: Long,
)
