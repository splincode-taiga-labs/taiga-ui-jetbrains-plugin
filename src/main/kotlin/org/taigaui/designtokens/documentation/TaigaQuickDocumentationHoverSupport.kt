package org.taigaui.designtokens.documentation

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Point
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.milliseconds

internal class TaigaQuickDocumentationHoverPopupListener :
    EditorMouseListener,
    EditorMouseMotionListener {
    override fun mousePressed(event: EditorMouseEvent) {
        event.dismissTaigaQuickDocumentationHover()
    }

    override fun mouseDragged(event: EditorMouseEvent) {
        event.dismissTaigaQuickDocumentationHover()
    }

    override fun mouseExited(event: EditorMouseEvent) {
        val editor = event.editor
        val project = editor.project ?: return

        project.service<TaigaQuickDocumentationHoverController>().mouseExited(editor)
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
    private val nativeHoverSuppression = DesignTokenNativeHoverPopupSuppression()
    private val underline = TaigaQuickDocumentationUnderline()
    private var activeKey: TaigaQuickDocumentationHoverKey? = null
    private var hoverJob: Job? = null
    private var hideJob: Job? = null
    private var popup: JBPopup? = null
    private var popupContent: TaigaQuickDocumentationPopupPanel? = null

    fun mouseMoved(event: EditorMouseEvent) {
        val request = event.toTaigaQuickDocumentationHoverRequest(project)

        if (request == null) {
            underline.clear()

            if (popup?.isVisible == true) {
                scheduleHide()
            } else {
                clearHover()
            }
        } else {
            val service = project.service<TaigaDocsService>()
            val snapshot = service.cachedSnapshotFor(request.sourceFile)
            val resolved = snapshot?.resolve(request.documentationRequest)

            if (snapshot != null && resolved == null) {
                clearHover()
            } else {
                handleRequest(request, resolved)
            }
        }
    }

    fun dismissHover(editor: Editor? = null) {
        if (editor == null || editor.project == project) {
            cancelScheduledHide()
            clearHover()
        }
    }

    fun mouseExited(editor: Editor) {
        if (editor.project == project) {
            underline.clear()

            if (popup?.isVisible == true) {
                scheduleHide()
            } else {
                clearHover()
            }
        }
    }

    private fun handleRequest(
        request: TaigaQuickDocumentationHoverRequest,
        cachedResolved: TaigaResolvedDocumentation?,
    ) {
        cancelScheduledHide()

        if (cachedResolved != null || request.documentationRequest is TaigaDocumentationRequest.Entity) {
            underline.show(
                request.editor,
                request.documentationRequest.startOffset,
                request.documentationRequest.endOffset,
            )
        } else {
            underline.clear()
        }

        nativeHoverSuppression.suppress(request.editor)

        if (request.key != activeKey) {
            hoverJob?.cancel()
            hidePopup(restoreNativeHover = false)
            activeKey = request.key
            hoverJob = scheduleHover(request, cachedResolved)
        }
    }

    private fun scheduleHover(
        request: TaigaQuickDocumentationHoverRequest,
        cachedResolved: TaigaResolvedDocumentation?,
    ): Job =
        coroutineScope.launch(Dispatchers.Default + CoroutineName("Taiga UI quick documentation hover")) {
            delay(TAIGA_HOVER_SHOW_DELAY)

            val resolved =
                cachedResolved
                    ?: project
                        .service<TaigaDocsService>()
                        .snapshotFor(request.sourceFile)
                        ?.resolve(request.documentationRequest)

            withContext(Dispatchers.EDT) {
                if (resolved == null || activeKey != request.key || !request.isStillCurrent(project)) {
                    if (activeKey == request.key) {
                        clearHover()
                    }
                } else {
                    hoverJob = null
                    underline.show(
                        request.editor,
                        request.documentationRequest.startOffset,
                        request.documentationRequest.endOffset,
                    )
                    showPopup(request, resolved)
                }
            }
        }

    private fun showPopup(
        request: TaigaQuickDocumentationHoverRequest,
        resolved: TaigaResolvedDocumentation,
    ) {
        val canShow =
            activeKey == request.key &&
                !project.isDisposed &&
                !request.editor.isDisposed

        if (canShow) {
            nativeHoverSuppression.suppress(request.editor)

            val panel =
                TaigaQuickDocumentationPopupPanel(
                    resolved = resolved,
                    onClose = { dismissHover(request.editor) },
                )
            val createdPopup =
                JBPopupFactory
                    .getInstance()
                    .createComponentPopupBuilder(panel, panel)
                    .setProject(project)
                    .setRequestFocus(false)
                    .setFocusable(false)
                    .setCancelOnClickOutside(true)
                    .setCancelOnOtherWindowOpen(true)
                    .setCancelOnWindowDeactivation(true)
                    .setMovable(false)
                    .setResizable(false)
                    .createPopup()

            createdPopup.addListener(
                object : JBPopupListener {
                    override fun onClosed(event: LightweightWindowEvent) {
                        if (popup === createdPopup) {
                            popup = null
                            popupContent = null
                            activeKey = null
                            hoverJob = null
                            cancelScheduledHide()
                            underline.clear()
                            nativeHoverSuppression.restore()
                        }
                    }
                },
            )

            popup = createdPopup
            popupContent = panel
            createdPopup.showInScreenCoordinates(
                request.editor.contentComponent,
                request.popupLocation(),
            )
            createdPopup.moveToFitScreen()
        }
    }

    private fun scheduleHide() {
        if (popup?.isVisible == true) {
            hideJob?.cancel()
            hideJob =
                coroutineScope.launch(Dispatchers.EDT + CoroutineName("Taiga UI quick documentation hover hide")) {
                    delay(HOVER_HIDE_GRACE_PERIOD)
                    hideJob = null

                    if (popupContent?.containsPointer() != true) {
                        clearHover()
                    }
                }
        } else {
            clearHover()
        }
    }

    private fun cancelScheduledHide() {
        hideJob?.cancel()
        hideJob = null
    }

    private fun clearHover() {
        activeKey = null
        hoverJob?.cancel()
        hoverJob = null
        underline.clear()
        hidePopup(restoreNativeHover = true)
    }

    private fun hidePopup(restoreNativeHover: Boolean) {
        val currentPopup = popup

        popup = null
        popupContent = null
        currentPopup?.cancel()

        if (restoreNativeHover) {
            nativeHoverSuppression.restore()
        }
    }
}

private fun EditorMouseEvent.toTaigaQuickDocumentationHoverRequest(
    project: Project,
): TaigaQuickDocumentationHoverRequest? {
    if (!canStartTaigaDocumentationHover(project)) {
        return null
    }

    val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
    val request =
        file?.let { psiFile ->
            ReadAction.compute<TaigaDocumentationRequest?, RuntimeException> {
                TaigaDocumentationResolver.findRequest(psiFile, offset)
            }
        }
    val sourceFile = file?.sourcePath()

    return if (request != null && sourceFile != null) {
        TaigaQuickDocumentationHoverRequest(
            editor = editor,
            anchor = Point(mouseEvent.point),
            sourceFile = sourceFile,
            documentationRequest = request,
            modificationStamp = editor.document.modificationStamp,
        )
    } else {
        null
    }
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

private fun TaigaQuickDocumentationHoverRequest.isStillCurrent(project: Project): Boolean {
    val projectState =
        editor.project == project &&
            !project.isDisposed &&
            !editor.isDisposed
    val documentState =
        !editor.selectionModel.hasSelection() &&
            editor.document.modificationStamp == modificationStamp
    val uiState =
        LookupManager.getInstance(project).activeLookup == null &&
            EditorSettingsExternalizable
                .getInstance()
                .isShowQuickDocOnMouseOverElement

    return projectState && documentState && uiState
}

private fun TaigaQuickDocumentationHoverRequest.popupLocation(): Point {
    val point = Point(anchor)

    point.y += editor.lineHeight
    SwingUtilities.convertPointToScreen(point, editor.contentComponent)

    return point
}

private val TaigaQuickDocumentationHoverRequest.key: TaigaQuickDocumentationHoverKey
    get() =
        TaigaQuickDocumentationHoverKey(
            editor = editor,
            startOffset = documentationRequest.startOffset,
            endOffset = documentationRequest.endOffset,
            modificationStamp = modificationStamp,
        )

private data class TaigaQuickDocumentationHoverRequest(
    val editor: Editor,
    val anchor: Point,
    val sourceFile: Path,
    val documentationRequest: TaigaDocumentationRequest,
    val modificationStamp: Long,
)

private data class TaigaQuickDocumentationHoverKey(
    val editor: Editor,
    val startOffset: Int,
    val endOffset: Int,
    val modificationStamp: Long,
)

private val HOVER_HIDE_GRACE_PERIOD = 250.milliseconds
