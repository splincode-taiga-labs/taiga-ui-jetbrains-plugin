package org.taigaui.designtokens.documentation

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.ide.BrowserUtil
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
import com.intellij.openapi.editor.impl.EditorMouseHoverPopupControl
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.MouseInfo
import java.awt.Point
import java.nio.file.Path
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
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

    fun mouseMoved(event: EditorMouseEvent) {
        val request = event.toTaigaQuickDocumentationHoverRequest(project)
        val requestKey = request?.key

        if (request == null) {
            if (popup?.isVisible == true) {
                scheduleHide()
            } else {
                clearHover()
            }

            return
        }

        cancelScheduledHide()
        suppressNativeHover(request.editor)

        if (requestKey != activeKey) {
            activeKey = requestKey
            hoverJob?.cancel()
            hidePopup(restoreNativeHover = false)
            hoverJob = scheduleHover(request)
        }
    }

    fun dismissHover(editor: Editor? = null) {
        if (editor == null || editor.project == project) {
            cancelScheduledHide()
            clearHover()
        }
    }

    private fun scheduleHover(request: TaigaQuickDocumentationHoverRequest): Job =
        coroutineScope.launch(Dispatchers.Default + CoroutineName("Taiga UI quick documentation hover")) {
            delay(TAIGA_HOVER_SHOW_DELAY)

            val service = project.service<TaigaDocsService>()
            val snapshot =
                service.cachedSnapshotFor(request.sourceFile)
                    ?: service.snapshotFor(request.sourceFile)
                    ?: return@launch clearRequestIfCurrent(request.key)
            val entity = snapshot.find(request.subject) ?: return@launch clearRequestIfCurrent(request.key)
            val subject = request.subject.completedFrom(entity)

            withContext(Dispatchers.EDT) {
                if (activeKey != request.key || !request.isStillCurrent(project)) {
                    clearRequestIfCurrent(request.key)
                    return@withContext
                }

                if (JBPopupFactory.getInstance().isPopupActive && popup?.isVisible != true) {
                    clearRequestIfCurrent(request.key)
                    return@withContext
                }

                hoverJob = null
                showPopup(request, entity, subject)
            }
        }

    private fun showPopup(
        request: TaigaQuickDocumentationHoverRequest,
        entity: TaigaEntityDoc,
        subject: TaigaDocumentationSubject,
    ) {
        if (activeKey != request.key || project.isDisposed || request.editor.isDisposed) {
            return
        }

        hidePopup(restoreNativeHover = false)
        suppressNativeHover(request.editor)

        val panel =
            TaigaQuickDocumentationPopupPanel(
                entity = entity,
                subject = subject,
                onOpenDocumentation = {
                    BrowserUtil.browse(entity.documentationUri.toString())
                    dismissHover(request.editor)
                },
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

    private fun clearRequestIfCurrent(key: TaigaQuickDocumentationHoverKey) {
        coroutineScope.launch(Dispatchers.EDT) {
            if (activeKey == key) {
                clearHover()
            }
        }
    }

    private fun clearHover() {
        activeKey = null
        hoverJob?.cancel()
        hoverJob = null
        hidePopup(restoreNativeHover = true)
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
    val match =
        ReadAction.compute<TaigaDocumentationMatch?, RuntimeException> {
            TaigaTemplateDocumentationResolver.findMatch(file, offset)
        } ?: return null
    val sourceFile = file.sourcePath() ?: return null

    return TaigaQuickDocumentationHoverRequest(
        editor = editor,
        anchor = Point(mouseEvent.point),
        sourceFile = sourceFile,
        subject = match.subject,
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
            startOffset = startOffset,
            endOffset = endOffset,
            modificationStamp = modificationStamp,
        )

private data class TaigaQuickDocumentationHoverRequest(
    val editor: Editor,
    val anchor: Point,
    val sourceFile: Path,
    val subject: TaigaDocumentationSubject,
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

private class TaigaQuickDocumentationPopupPanel(
    entity: TaigaEntityDoc,
    subject: TaigaDocumentationSubject,
    onOpenDocumentation: () -> Unit,
) : JPanel(BorderLayout()) {
    init {
        border = JBUI.Borders.empty(12, 16)
        isOpaque = true

        val packageName = subject.packageName ?: entity.packageNames.singleOrNull()
        val content =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }

        val title =
            JBLabel(subject.presentationName).apply {
                font = font.deriveFont(font.style or java.awt.Font.BOLD)
                alignmentX = LEFT_ALIGNMENT
            }
        content.add(title)

        packageName?.let { name ->
            content.add(Box.createVerticalStrut(JBUI.scale(2)))
            content.add(
                JBLabel(name).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    alignmentX = LEFT_ALIGNMENT
                },
            )
        }

        entity.description?.takeIf(String::isNotBlank)?.let { description ->
            content.add(Box.createVerticalStrut(JBUI.scale(10)))
            content.add(
                JBLabel(
                    "<html><div width='${POPUP_TEXT_WIDTH}'>${description.html()}</div></html>",
                ).apply {
                    alignmentX = LEFT_ALIGNMENT
                },
            )
        }

        val details = entity.hoverDetails(subject)
        if (details.isNotEmpty()) {
            content.add(Box.createVerticalStrut(JBUI.scale(10)))
            details.forEach { (label, value) ->
                content.add(
                    JBLabel(
                        "<html><b>${label.html()}:</b>&nbsp;&nbsp;$value</html>",
                    ).apply {
                        alignmentX = LEFT_ALIGNMENT
                    },
                )
                content.add(Box.createVerticalStrut(JBUI.scale(4)))
            }
        }

        content.add(Box.createVerticalStrut(JBUI.scale(6)))
        content.add(
            LinkLabel<Any>("Open Taiga UI documentation", null) { _, _ ->
                onOpenDocumentation()
            }.apply {
                alignmentX = LEFT_ALIGNMENT
            },
        )

        add(content, BorderLayout.CENTER)
    }

    fun containsPointer(): Boolean {
        if (!isShowing) {
            return false
        }

        val pointer = MouseInfo.getPointerInfo()?.location ?: return false
        val local = Point(pointer)

        SwingUtilities.convertPointFromScreen(local, this)

        return contains(local)
    }
}

private fun TaigaEntityDoc.hoverDetails(
    subject: TaigaDocumentationSubject,
): List<Pair<String, String>> =
    buildList {
        (subject.selector ?: selectors.singleOrNull())?.let { selector ->
            add("Selector" to "<code>${selector.html()}</code>")
        }

        inputs.takeIf(List<TaigaApiProperty>::isNotEmpty)?.let { properties ->
            add("Inputs" to properties.toCompactApiHtml())
        }

        outputs.takeIf(List<TaigaApiProperty>::isNotEmpty)?.let { properties ->
            add("Outputs" to properties.toCompactApiHtml())
        }
    }

private fun List<TaigaApiProperty>.toCompactApiHtml(): String {
    val visible = take(MAX_VISIBLE_API_PROPERTIES)
    val remaining = size - visible.size
    val rendered =
        visible.joinToString(",&nbsp; ") { property ->
            buildString {
                append("<code>")
                append(property.name.html())
                property.documentedType?.let { type ->
                    append(": ")
                    append(type.html())
                }
                append("</code>")
            }
        }

    return if (remaining > 0) {
        "$rendered <span style='color:gray'>+$remaining more</span>"
    } else {
        rendered
    }
}

private fun String.html(): String = StringUtil.escapeXmlEntities(this)

private val HOVER_HIDE_GRACE_PERIOD = 250.milliseconds
private const val POPUP_TEXT_WIDTH = 520
private const val MAX_VISIBLE_API_PROPERTIES = 4
