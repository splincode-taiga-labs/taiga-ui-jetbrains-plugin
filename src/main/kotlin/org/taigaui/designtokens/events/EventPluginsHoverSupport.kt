package org.taigaui.designtokens.events

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
import com.intellij.openapi.editor.impl.EditorMouseHoverPopupControl
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.taigaui.designtokens.documentation.DesignTokenReferenceHitTester
import java.awt.BorderLayout
import java.awt.Point
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.seconds

internal class EventPluginsHoverPopupListener :
    EditorMouseListener,
    EditorMouseMotionListener {
    override fun mousePressed(event: EditorMouseEvent) {
        event.dismissEventPluginsHoverPopup()
    }

    override fun mouseDragged(event: EditorMouseEvent) {
        event.dismissEventPluginsHoverPopup()
    }

    override fun mouseMoved(event: EditorMouseEvent) {
        val editor = event.editor
        val project = editor.project ?: return

        if (LookupManager.getInstance(project).activeLookup == null) {
            project.service<EventPluginsHoverPopupController>().mouseMoved(event)
        } else {
            project.service<EventPluginsHoverPopupController>().dismissHover(editor)
        }
    }
}

private fun EditorMouseEvent.dismissEventPluginsHoverPopup() {
    val project = editor.project ?: return

    project.service<EventPluginsHoverPopupController>().dismissHover(editor)
}

@Service(Service.Level.PROJECT)
internal class EventPluginsHoverPopupController(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    private var activeKey: EventPluginsHoverKey? = null
    private var hoverJob: Job? = null
    private var popup: JBPopup? = null
    private var nativeHoverSuppressedEditor: Editor? = null

    fun mouseMoved(event: EditorMouseEvent) {
        val request = event.toEventPluginsHoverRequest(project)
        val requestKey = request?.key

        if (requestKey == null) {
            dismissHover(event.editor)
        } else {
            suppressNativeHover(request.editor)

            if (requestKey != activeKey) {
                activeKey = requestKey
                hoverJob?.cancel()
                hidePopup()
                hoverJob = scheduleHover(request)
            }
        }
    }

    fun dismissHover() {
        dismissHover(null)
    }

    fun dismissHover(editor: Editor?) {
        if (editor == null || editor.project == project) {
            activeKey = null
            hoverJob?.cancel()
            hoverJob = null
            hidePopup()
            restoreNativeHover()
        }
    }

    private fun scheduleHover(request: EventPluginsHoverRequest): Job =
        coroutineScope.launch(Dispatchers.EDT + CoroutineName("Taiga UI event plugins hover documentation")) {
            delay(EVENT_PLUGINS_HOVER_DELAY)

            if (!request.isStillCurrent(project)) {
                clearIfCurrent(request.key)
                return@launch
            }

            if (activeKey == request.key) {
                showPopup(request)
            }
        }

    private fun showPopup(request: EventPluginsHoverRequest) {
        if (popup?.isVisible == true || JBPopupFactory.getInstance().isPopupActive) {
            return
        }

        val panel = EventPluginsHoverPopupPanel(request.reference.binding)
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

    private fun clearIfCurrent(key: EventPluginsHoverKey) {
        if (activeKey == key) {
            activeKey = null
            hoverJob = null
            hidePopup()
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

private fun EditorMouseEvent.toEventPluginsHoverRequest(project: Project): EventPluginsHoverRequest? =
    takeIf { area == EditorMouseEventArea.EDITING_AREA }
        ?.takeIf { editor.canShowEventPluginsHover(project) }
        ?.let { event ->
            event.eventPluginBindingUnderPointer()?.let { reference ->
                EventPluginsHoverRequest(
                    editor = editor,
                    reference = reference,
                    anchor = Point(mouseEvent.point),
                    modificationStamp = editor.document.modificationStamp,
                )
            }
        }

private fun EditorMouseEvent.eventPluginBindingUnderPointer(): EventPluginBindingAtOffset? {
    val text = editor.document.immutableCharSequence
    val textReference = EventPluginBindingAtOffsetFinder.find(text, offset)
    val reference =
        textReference
            ?: editor.project?.let { project ->
                ReadAction.compute<EventPluginBindingAtOffset?, RuntimeException> {
                    val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)

                    EventPluginBindingAtOffsetFinder.find(
                        file = file,
                        text = text,
                        offset = offset,
                    )
                }
            }

    return reference?.takeIf { candidate -> editor.isPointerOver(candidate, mouseEvent.point) }
}

private fun EventPluginsHoverRequest.isStillCurrent(project: Project): Boolean =
    editor.canShowEventPluginsHover(project) &&
        editor.document.modificationStamp == modificationStamp

private fun Editor.canShowEventPluginsHover(project: Project): Boolean =
    this.project == project &&
        !project.isDisposed &&
        !isDisposed &&
        !selectionModel.hasSelection() &&
        LookupManager.getInstance(project).activeLookup == null

private fun Editor.isPointerOver(
    reference: EventPluginBindingAtOffset,
    pointer: Point,
): Boolean =
    DesignTokenReferenceHitTester.contains(
        start = offsetToXY(reference.startOffset),
        end = offsetToXY(reference.endOffset),
        lineHeight = lineHeight,
        pointer = pointer,
    )

private fun EventPluginsHoverRequest.popupLocation(): Point {
    val point = Point(anchor)

    SwingUtilities.convertPointToScreen(point, editor.contentComponent)

    return Point(point.x, point.y + editor.lineHeight)
}

private val EventPluginsHoverRequest.key: EventPluginsHoverKey
    get() =
        EventPluginsHoverKey(
            editor = editor,
            startOffset = reference.startOffset,
            source = reference.binding.source,
            modificationStamp = modificationStamp,
        )

private data class EventPluginsHoverRequest(
    val editor: Editor,
    val reference: EventPluginBindingAtOffset,
    val anchor: Point,
    val modificationStamp: Long,
)

private data class EventPluginsHoverKey(
    val editor: Editor,
    val startOffset: Int,
    val source: String,
    val modificationStamp: Long,
)

internal data class EventPluginBindingAtOffset(
    val binding: EventPluginBinding,
    val startOffset: Int,
    val endOffset: Int,
)

internal object EventPluginBindingAtOffsetFinder {
    fun find(
        file: PsiFile?,
        text: CharSequence,
        offset: Int,
    ): EventPluginBindingAtOffset? =
        find(text, offset)
            ?: file
                ?.let { AngularHostBindingSupport.findAt(it, offset) }
                ?.let { hostBinding ->
                    EventPluginBinding
                        .parse(hostBinding.source)
                        ?.let { binding ->
                            EventPluginBindingAtOffset(
                                binding = binding,
                                startOffset = hostBinding.startOffset,
                                endOffset = hostBinding.endOffset,
                            )
                        }
                }

    fun find(
        text: CharSequence,
        offset: Int,
    ): EventPluginBindingAtOffset? =
        if (text.isEmpty() || offset !in 0..text.length) {
            null
        } else {
            val searchOffset = offset.coerceAtMost(text.lastIndex)
            val range = findBindingRange(text, searchOffset)

            if (range == null || !hasAttributeValue(text, range.last)) {
                null
            } else {
                val source = text.subSequence(range.first, range.last + 1).toString()

                EventPluginBinding
                    .parse(source)
                    ?.let { binding ->
                        EventPluginBindingAtOffset(
                            binding = binding,
                            startOffset = range.first,
                            endOffset = range.last + 1,
                        )
                    }
            }
        }

    private fun findBindingRange(
        text: CharSequence,
        offset: Int,
    ): IntRange? {
        val start = findOpeningParenthesis(text, offset)
        val end = findClosingParenthesis(text, offset)

        return if (start != null && end != null && offset in start..end) {
            start..end
        } else {
            null
        }
    }

    private fun findOpeningParenthesis(
        text: CharSequence,
        offset: Int,
    ): Int? =
        (offset downTo maxOf(0, offset - MAX_BINDING_LENGTH))
            .firstOrNull { index -> text[index] == '(' || text[index].isBindingBoundary() }
            ?.takeIf { index -> text[index] == '(' }

    private fun findClosingParenthesis(
        text: CharSequence,
        offset: Int,
    ): Int? =
        (offset..minOf(text.lastIndex, offset + MAX_BINDING_LENGTH))
            .firstOrNull { index -> text[index] == ')' || text[index].isBindingBoundary() }
            ?.takeIf { index -> text[index] == ')' }

    private fun hasAttributeValue(
        text: CharSequence,
        closingParenthesis: Int,
    ): Boolean {
        var index = closingParenthesis + 1

        while (index < text.length && text[index].isWhitespace()) {
            index++
        }

        return index < text.length && text[index] == '='
    }

    private fun Char.isBindingBoundary(): Boolean =
        isWhitespace() || this == '<' || this == '>' || this == '=' || this == '"' || this == '\''

    private const val MAX_BINDING_LENGTH = 160
}

private class EventPluginsHoverPopupPanel(
    binding: EventPluginBinding,
) : JPanel(BorderLayout()) {
    init {
        border = JBUI.Borders.empty(12, 16)
        add(JBLabel(binding.toHtml()), BorderLayout.CENTER)
    }
}

private fun EventPluginBinding.toHtml(): String =
    buildString {
        append("<html><table width='480' cellspacing='0' cellpadding='0'><tr><td>")
        append("<b><code>")
        append(source.escapeHtml())
        append("</code></b><hr>")
        append("<b>Event:</b> <code>")
        append(event.escapeHtml())
        append("</code><br><br>")
        append("<b>Modifiers</b><br>")

        modifiers.forEach { modifier ->
            append("<code>")
            append(modifier.source.escapeHtml())
            append("</code> — ")
            append(modifier.description.escapeHtml())
            append("<br>")
        }

        append("<br><b>Combined behavior</b><br>")
        append(combinedBehavior.escapeHtml())
        append("<br><br><span style='color:gray'>Library: @taiga-ui/event-plugins</span>")
        append("</td></tr></table></html>")
    }

private fun String.escapeHtml(): String = StringUtil.escapeXmlEntities(this)

private val EVENT_PLUGINS_HOVER_DELAY = 1.seconds
