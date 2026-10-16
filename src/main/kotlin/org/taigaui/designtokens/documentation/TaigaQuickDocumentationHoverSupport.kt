package org.taigaui.designtokens.documentation

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.editor.impl.EditorMouseHoverPopupControl
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
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
import java.awt.Font
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
    private var activeKey: TaigaQuickDocumentationHoverKey? = null
    private var hoverJob: Job? = null
    private var hideJob: Job? = null
    private var popup: JBPopup? = null
    private var popupContent: TaigaQuickDocumentationPopupPanel? = null
    private var nativeHoverSuppressedEditor: Editor? = null
    private var hoverUnderline: HoverUnderline? = null

    fun mouseMoved(event: EditorMouseEvent) {
        val request = event.toTaigaQuickDocumentationHoverRequest(project)

        if (request == null) {
            clearHoverUnderline()

            if (popup?.isVisible == true) {
                scheduleHide()
            } else {
                clearHover()
            }

            return
        }

        val service = project.service<TaigaDocsService>()
        val cachedSnapshot = service.cachedSnapshotFor(request.sourceFile)
        val cachedResolved = cachedSnapshot?.resolve(request.documentationRequest)

        if (cachedSnapshot != null && cachedResolved == null) {
            clearHover()
            return
        }

        cancelScheduledHide()

        if (
            cachedResolved != null ||
            request.documentationRequest is TaigaDocumentationRequest.Entity
        ) {
            showHoverUnderline(request)
        } else {
            clearHoverUnderline()
        }

        if (request.key == activeKey) {
            suppressNativeHover(request.editor)
            return
        }

        hoverJob?.cancel()
        hidePopup(restoreNativeHover = false)
        activeKey = request.key
        suppressNativeHover(request.editor)
        hoverJob = scheduleHover(request, cachedResolved)
    }

    fun dismissHover(editor: Editor? = null) {
        if (editor == null || editor.project == project) {
            cancelScheduledHide()
            clearHover()
        }
    }

    fun mouseExited(editor: Editor) {
        if (editor.project != project) {
            return
        }

        clearHoverUnderline()

        if (popup?.isVisible == true) {
            scheduleHide()
        } else {
            clearHover()
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
                if (
                    resolved == null ||
                    activeKey != request.key ||
                    !request.isStillCurrent(project)
                ) {
                    clearIfCurrent(request.key)
                    return@withContext
                }

                hoverJob = null
                showHoverUnderline(request)
                showPopup(request, resolved)
            }
        }

    private fun showPopup(
        request: TaigaQuickDocumentationHoverRequest,
        resolved: TaigaResolvedDocumentation,
    ) {
        if (activeKey != request.key || project.isDisposed || request.editor.isDisposed) {
            return
        }

        suppressNativeHover(request.editor)

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
                        clearHoverUnderline()
                        restoreNativeHover()
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

    private fun scheduleHide() {
        if (popup?.isVisible != true) {
            clearHover()
            return
        }

        hideJob?.cancel()
        hideJob =
            coroutineScope.launch(Dispatchers.EDT + CoroutineName("Taiga UI quick documentation hover hide")) {
                delay(HOVER_HIDE_GRACE_PERIOD)
                hideJob = null

                if (popupContent?.containsPointer() != true) {
                    clearHover()
                }
            }
    }

    private fun cancelScheduledHide() {
        hideJob?.cancel()
        hideJob = null
    }

    private fun clearIfCurrent(key: TaigaQuickDocumentationHoverKey) {
        if (activeKey == key) {
            clearHover()
        }
    }

    private fun clearHover() {
        activeKey = null
        hoverJob?.cancel()
        hoverJob = null
        clearHoverUnderline()
        hidePopup(restoreNativeHover = true)
    }

    private fun showHoverUnderline(request: TaigaQuickDocumentationHoverRequest) {
        val current = hoverUnderline

        if (
            current?.editor === request.editor &&
            current.startOffset == request.documentationRequest.startOffset &&
            current.endOffset == request.documentationRequest.endOffset
        ) {
            return
        }

        clearHoverUnderline()

        val effectColor =
            request.editor.colorsScheme
                .getAttributes(EditorColors.REFERENCE_HYPERLINK_COLOR)
                ?.foregroundColor
                ?: request.editor.colorsScheme.defaultForeground
        val attributes =
            TextAttributes(
                null,
                null,
                effectColor,
                EffectType.LINE_UNDERSCORE,
                Font.PLAIN,
            )
        val highlighter =
            request.editor.markupModel.addRangeHighlighter(
                request.documentationRequest.startOffset,
                request.documentationRequest.endOffset,
                HighlighterLayer.HYPERLINK,
                attributes,
                HighlighterTargetArea.EXACT_RANGE,
            )

        hoverUnderline =
            HoverUnderline(
                editor = request.editor,
                highlighter = highlighter,
                startOffset = request.documentationRequest.startOffset,
                endOffset = request.documentationRequest.endOffset,
            )
    }

    private fun clearHoverUnderline() {
        val underline = hoverUnderline ?: return

        hoverUnderline = null

        if (!underline.editor.isDisposed) {
            underline.editor.markupModel.removeHighlighter(underline.highlighter)
        }
    }

    private fun hidePopup(restoreNativeHover: Boolean) {
        val currentPopup = popup

        popup = null
        popupContent = null
        currentPopup?.cancel()

        if (restoreNativeHover) {
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
    val documentationRequest =
        ReadAction.compute<TaigaDocumentationRequest?, RuntimeException> {
            TaigaDocumentationResolver.findRequest(file, offset)
        } ?: return null
    val sourceFile = file.sourcePath() ?: return null

    return TaigaQuickDocumentationHoverRequest(
        editor = editor,
        event = this,
        anchor = Point(mouseEvent.point),
        sourceFile = sourceFile,
        documentationRequest = documentationRequest,
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
    val event: EditorMouseEvent,
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

private data class HoverUnderline(
    val editor: Editor,
    val highlighter: RangeHighlighter,
    val startOffset: Int,
    val endOffset: Int,
)

private val HOVER_HIDE_GRACE_PERIOD = 250.milliseconds
