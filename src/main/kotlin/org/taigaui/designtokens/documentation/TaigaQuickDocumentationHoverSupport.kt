package org.taigaui.designtokens.documentation

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.taigaui.designtokens.icons.ICON_PREVIEW_LOGICAL_SIZE
import org.taigaui.designtokens.icons.IconCompletionService
import org.taigaui.designtokens.icons.IconSvgPreviewRenderer
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
@Suppress("TooManyFunctions")
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
    private var previewJob: Job? = null
    private val iconRenderer = IconSvgPreviewRenderer()
    private var resolutionJob: Job? = null
    private var pendingKey: TaigaQuickDocumentationHoverKey? = null
    private var currentRequest: TaigaQuickDocumentationHoverRequest? = null

    fun mouseMoved(event: EditorMouseEvent) {
        val candidate = event.toTaigaDocumentationHoverCandidate(project)

        if (candidate == null) {
            resolutionJob?.cancel()
            resolutionJob = null
            pendingKey = null
            hoverJob?.cancel()
            hoverJob = null
            underline.clear()

            if (popup?.isVisible == true) {
                scheduleHide()
            } else {
                clearHover()
            }
        } else if (candidate.key == pendingKey) {
            cancelScheduledHide()
            currentRequest?.takeIf { it.documentationRequest.shouldUnderline }?.let { request ->
                underline.show(
                    request.editor,
                    request.documentationRequest.startOffset,
                    request.documentationRequest.endOffset,
                )
            }
        } else {
            resolutionJob?.cancel()
            pendingKey = candidate.key
            resolutionJob = resolveCandidate(candidate)
        }
    }

    private fun resolveCandidate(candidate: TaigaDocumentationHoverCandidate): Job =
        coroutineScope.launch(Dispatchers.Default + CoroutineName("Taiga UI documentation PSI resolution")) {
            val request =
                ReadAction.compute<TaigaQuickDocumentationHoverRequest?, RuntimeException> {
                    if (project.isDisposed || !candidate.file.isValid) null else candidate.resolveRequest()
                }
            val snapshot = request?.let { project.service<TaigaDocsService>().cachedSnapshotFor(it.sourceFile) }
            val resolved = request?.let { snapshot?.resolve(it.documentationRequest) }
            withContext(Dispatchers.EDT) {
                if (pendingKey == candidate.key) {
                    if (request == null) {
                        underline.clear()
                        if (popup?.isVisible == true) scheduleHide() else clearHover()
                    } else if (!request.isStillCurrent(project) || (snapshot != null && resolved == null)) {
                        clearHover()
                    } else {
                        handleRequest(request, resolved)
                    }
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
            resolutionJob?.cancel()
            resolutionJob = null
            pendingKey = null
            hoverJob?.cancel()
            hoverJob = null
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
        currentRequest = request
        cancelScheduledHide()

        if (request.documentationRequest.shouldUnderline) {
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
                    if (request.documentationRequest.shouldUnderline) {
                        underline.show(
                            request.editor,
                            request.documentationRequest.startOffset,
                            request.documentationRequest.endOffset,
                        )
                    } else {
                        underline.clear()
                    }
                    showPopup(request, resolved)
                }
            }
        }

    private fun showPopup(
        request: TaigaQuickDocumentationHoverRequest,
        resolved: TaigaResolvedDocumentation,
        showExample: Boolean = false,
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
                    actions = popupActions(request, resolved),
                    showExample = showExample,
                )
            val createdPopup =
                JBPopupFactory
                    .getInstance()
                    .createComponentPopupBuilder(panel, panel)
                    .setProject(project)
                    .setRequestFocus(false)
                    .setFocusable(true)
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
                            pendingKey = null
                            currentRequest = null
                            previewJob?.cancel()
                            previewJob = null
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
            loadIconPreviews(request, resolved, panel)
        }
    }

    private fun popupActions(
        request: TaigaQuickDocumentationHoverRequest,
        resolved: TaigaResolvedDocumentation,
    ): TaigaDocumentationPopupActions =
        TaigaDocumentationPopupActions(
            navigateToSource =
                resolved.source?.let { source ->
                    {
                        dismissHover(request.editor)
                        LocalFileSystem.getInstance().findFileByNioFile(source.file)?.let { file ->
                            OpenFileDescriptor(project, file, source.offset).navigate(true)
                        }
                    }
                },
            chooseIcon = { reference -> chooseIcon(request, reference) },
            openMember = { member ->
                hidePopup(restoreNativeHover = false)
                showPopup(request, member)
            },
            showExample = {
                hidePopup(restoreNativeHover = false)
                showPopup(request, resolved, showExample = true)
            },
        )

    private fun loadIconPreviews(
        request: TaigaQuickDocumentationHoverRequest,
        resolved: TaigaResolvedDocumentation,
        panel: TaigaQuickDocumentationPopupPanel,
    ) {
        val references = (resolved as? TaigaResolvedDocumentation.Entity)?.icons.orEmpty().take(2)
        if (references.isEmpty()) return

        previewJob =
            coroutineScope.launch(Dispatchers.IO + CoroutineName("Taiga UI documentation icon preview")) {
                val service = project.service<IconCompletionService>()
                service.loadNow(request.sourceFile)
                val previews =
                    references.mapNotNull { reference ->
                        service
                            .svgSourceFor(request.sourceFile, reference.name)
                            ?.let { source -> iconRenderer.render(source, ICON_PREVIEW_LOGICAL_SIZE) }
                            ?.let { icon -> TaigaDocumentationIconPreview(reference, icon) }
                    }
                withContext(Dispatchers.EDT) {
                    if (popupContent === panel && request.isStillCurrent(project)) {
                        panel.showIconPreviews(previews)
                        popup?.setSize(panel.preferredSize)
                        popup?.moveToFitScreen()
                    }
                }
            }
    }

    private fun chooseIcon(
        request: TaigaQuickDocumentationHoverRequest,
        reference: TaigaDocumentationIcon,
    ) {
        dismissHover(request.editor)
        coroutineScope.launch(Dispatchers.IO + CoroutineName("Taiga UI documentation icon chooser")) {
            val names = project.service<IconCompletionService>().loadNow(request.sourceFile)
            withContext(Dispatchers.EDT) {
                if (request.isStillCurrent(project)) {
                    if (names.isEmpty()) {
                        Messages.showInfoMessage(project, "No Taiga UI icons were found in this project.", "Taiga UI")
                    } else {
                        JBPopupFactory
                            .getInstance()
                            .createPopupChooserBuilder(names)
                            .setTitle("Choose Taiga UI icon")
                            .setNamerForFiltering { it }
                            .setItemChosenCallback { name -> replaceIcon(request, reference, name) }
                            .createPopup()
                            .showInBestPositionFor(request.editor)
                    }
                }
            }
        }
    }

    private fun replaceIcon(
        request: TaigaQuickDocumentationHoverRequest,
        reference: TaigaDocumentationIcon,
        name: String,
    ) {
        if (!request.isStillCurrent(project)) return
        WriteCommandAction.runWriteCommandAction(project) {
            val document = request.editor.document
            val current = document.charsSequence
            if (reference.endOffset <= current.length &&
                current.subSequence(reference.startOffset, reference.endOffset).toString() == reference.name
            ) {
                document.replaceString(reference.startOffset, reference.endOffset, name)
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
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
        resolutionJob?.cancel()
        resolutionJob = null
        pendingKey = null
        currentRequest = null
        activeKey = null
        hoverJob?.cancel()
        hoverJob = null
        underline.clear()
        hidePopup(restoreNativeHover = true)
    }

    private fun hidePopup(restoreNativeHover: Boolean) {
        previewJob?.cancel()
        previewJob = null
        val currentPopup = popup

        popup = null
        popupContent = null
        currentPopup?.cancel()

        if (restoreNativeHover) {
            nativeHoverSuppression.restore()
        }
    }
}

@Suppress("ReturnCount")
private fun EditorMouseEvent.toTaigaDocumentationHoverCandidate(project: Project): TaigaDocumentationHoverCandidate? {
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

private fun TaigaDocumentationHoverCandidate.resolveRequest(): TaigaQuickDocumentationHoverRequest? {
    val request = TaigaDocumentationResolver.findRequest(file, offset)
    val sourceFile = file.sourcePath()

    return if (request != null && sourceFile != null) {
        TaigaQuickDocumentationHoverRequest(
            editor = editor,
            anchor = anchor,
            sourceFile = sourceFile,
            documentationRequest = request,
            modificationStamp = key.modificationStamp,
        )
    } else {
        null
    }
}

private data class TaigaDocumentationHoverCandidate(
    val file: PsiFile,
    val editor: Editor,
    val anchor: Point,
    val offset: Int,
    val key: TaigaQuickDocumentationHoverKey,
)

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

private val TaigaDocumentationRequest.shouldUnderline: Boolean
    get() =
        this is TaigaDocumentationRequest.Entity &&
            subjects.any { subject -> subject.selector != null }

private val HOVER_HIDE_GRACE_PERIOD = 250.milliseconds
