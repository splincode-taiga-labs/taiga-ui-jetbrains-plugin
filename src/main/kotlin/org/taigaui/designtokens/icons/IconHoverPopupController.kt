package org.taigaui.designtokens.icons

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.impl.EditorMouseHoverPopupControl
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.taigaui.designtokens.documentation.DesignTokenReferenceHitTester
import org.taigaui.designtokens.documentation.TaigaQuickDocumentationUnderline
import java.awt.Point
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.seconds

@Service(Service.Level.PROJECT)
internal class IconHoverPopupController(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    private val renderer = IconSvgPreviewRenderer()
    private val hoverHighlight = TaigaQuickDocumentationUnderline()
    private var activeKey: IconHoverKey? = null
    private var hoverJob: Job? = null
    private var popup: JBPopup? = null
    private var nativeHoverSuppressedEditor: Editor? = null

    fun mouseMoved(event: EditorMouseEvent) {
        val request = event.toIconHoverRequest(project)
        val requestKey = request?.key

        if (requestKey == null) {
            dismissHover(event.editor)
        } else {
            hoverHighlight.show(request.editor, request.reference.startOffset, request.reference.endOffset)
            suppressNativeHover(request.editor)

            if (requestKey != activeKey) {
                activeKey = requestKey
                hoverJob?.cancel()
                hidePopup()
                hoverJob = scheduleHover(request)
            }
        }
    }

    fun dismissHover(editor: Editor? = null) {
        if (editor == null || editor.project == project) {
            activeKey = null
            hoverJob?.cancel()
            hoverJob = null
            hidePopup()
            hoverHighlight.clear()
            restoreNativeHover()
        }
    }

    private fun scheduleHover(request: IconHoverRequest): Job =
        coroutineScope.launch(Dispatchers.EDT + CoroutineName("Taiga UI icon hover preview")) {
            delay(ICON_HOVER_DELAY)

            if (!request.isStillCurrent(project)) {
                clearIfCurrent(request.key)
                return@launch
            }

            val icon =
                withContext(Dispatchers.IO) {
                    val service = project.service<IconCompletionService>()

                    service.loadNow(request.sourceFile)
                    service
                        .svgSourceFor(request.sourceFile, request.reference.name)
                        ?.let { source -> renderer.render(source, ICON_PREVIEW_LOGICAL_SIZE) }
                }

            if (icon == null) {
                clearIfCurrent(request.key)
            } else if (request.isStillCurrent(project) && activeKey == request.key) {
                showPopup(request, icon)
            }
        }

    private fun showPopup(
        request: IconHoverRequest,
        icon: Icon,
    ) {
        if (popup?.isVisible == true || JBPopupFactory.getInstance().isPopupActive) {
            return
        }

        val panel = IconCompletionPreviewPanel().apply { showIcon(request.reference.name, icon) }
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
                        activeKey = null
                        hoverJob = null
                        hoverHighlight.clear()
                        restoreNativeHover()
                    }
                }
            },
        )
        popup = createdPopup
        createdPopup.showInScreenCoordinates(
            request.editor.contentComponent,
            request.popupLocation(),
        )
        createdPopup.moveToFitScreen()
    }

    private fun clearIfCurrent(key: IconHoverKey) {
        if (activeKey == key) {
            activeKey = null
            hoverJob = null
            hidePopup()
            hoverHighlight.clear()
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

    private fun hidePopup() {
        val currentPopup = popup

        popup = null
        currentPopup?.cancel()
    }
}

private fun EditorMouseEvent.toIconHoverRequest(project: Project): IconHoverRequest? =
    takeIf { area == EditorMouseEventArea.EDITING_AREA }
        ?.takeIf { editor.canShowIconHover(project) }
        ?.let { event ->
            editor.iconSourceFile()?.let { sourceFile ->
                event.iconReferenceUnderPointer()?.let { reference ->
                    IconHoverRequest(
                        editor = editor,
                        sourceFile = sourceFile,
                        reference = reference,
                        anchor = Point(mouseEvent.point),
                        modificationStamp = editor.document.modificationStamp,
                    )
                }
            }
        }

private fun EditorMouseEvent.iconReferenceUnderPointer(): IconReferenceAtOffset? =
    IconReferenceAtOffsetFinder
        .find(editor.document.immutableCharSequence, offset)
        ?.takeIf { reference -> editor.isPointerOver(reference, mouseEvent.point) }

private fun IconHoverRequest.isStillCurrent(project: Project): Boolean =
    editor.canShowIconHover(project) &&
        editor.document.modificationStamp == modificationStamp

private fun Editor.canShowIconHover(project: Project): Boolean =
    this.project == project &&
        !project.isDisposed &&
        !hasIconHoverConflict(project)

private fun Editor.hasIconHoverConflict(project: Project): Boolean =
    isDisposed ||
        selectionModel.hasSelection() ||
        LookupManager.getInstance(project).activeLookup != null

private fun Editor.iconSourceFile(): Path? =
    FileDocumentManager
        .getInstance()
        .getFile(document)
        ?.takeIf { file -> file.extension?.lowercase() in ICON_SUPPORTED_EXTENSIONS }
        ?.path
        ?.let { path -> runCatching { Path.of(path) }.getOrNull() }

private fun Editor.isPointerOver(
    reference: IconReferenceAtOffset,
    pointer: Point,
): Boolean =
    DesignTokenReferenceHitTester.contains(
        start = offsetToXY(reference.startOffset),
        end = offsetToXY(reference.endOffset),
        lineHeight = lineHeight,
        pointer = pointer,
    )

private fun IconHoverRequest.popupLocation(): Point {
    val point = Point(anchor)

    SwingUtilities.convertPointToScreen(point, editor.contentComponent)

    return Point(point.x, point.y + editor.lineHeight)
}

private val IconHoverRequest.key: IconHoverKey
    get() =
        IconHoverKey(
            editor = editor,
            iconName = reference.name,
            startOffset = reference.startOffset,
            modificationStamp = modificationStamp,
        )

private data class IconHoverRequest(
    val editor: Editor,
    val sourceFile: Path,
    val reference: IconReferenceAtOffset,
    val anchor: Point,
    val modificationStamp: Long,
)

private data class IconHoverKey(
    val editor: Editor,
    val iconName: String,
    val startOffset: Int,
    val modificationStamp: Long,
)

private val ICON_HOVER_DELAY = 1.seconds
