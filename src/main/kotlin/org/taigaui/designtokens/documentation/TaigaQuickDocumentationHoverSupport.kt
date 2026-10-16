package org.taigaui.designtokens.documentation

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiModificationTracker
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Dimension
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
@Suppress("TooManyFunctions", "LargeClass")
internal class TaigaQuickDocumentationHoverController(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : Disposable {
    private val nativeHoverSuppression = DesignTokenNativeHoverPopupSuppression()
    private val underline = TaigaQuickDocumentationUnderline()
    private var activeKey: TaigaQuickDocumentationHoverKey? = null
    private var hoverJob: Job? = null
    private var hideJob: Job? = null
    private var popup: JBPopup? = null
    private var popupContent: TaigaQuickDocumentationPopupPanel? = null
    private var popupHost: TaigaDocumentationPopupHost? = null
    private val resolutionStatus = TaigaDocumentationStatusPopup(project) { dismissHover(force = true) }
    private var pinnedSize: Dimension? = null
    private var pinnedLocation: Point? = null
    private var refreshInProgress = false
    private var previewJob: Job? = null
    private val icons = TaigaDocumentationIcons(project, coroutineScope)
    private var resolutionJob: Job? = null
    private var pendingKey: TaigaQuickDocumentationHoverKey? = null
    private var currentRequest: TaigaQuickDocumentationHoverRequest? = null

    private var pinned = false
    private val cardEditors = TaigaDocumentationCardEditors(project)
    private var currentView: TaigaDocumentationView? = null
    private val history = ArrayDeque<TaigaDocumentationView>()
    private var refreshTarget: TaigaDocumentationRefreshTarget? = null

    init {
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorReleased(event: EditorFactoryEvent) {
                    if (currentRequest?.editor === event.editor || resolutionStatus.editor === event.editor) {
                        dismissHover(force = true)
                    }
                }
            },
            project,
        )
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = contextChanged()
            },
            project,
        )
        project.messageBus.connect(project).subscribe(
            PsiModificationTracker.TOPIC,
            object : PsiModificationTracker.Listener {
                override fun modificationCountChanged() = contextChanged()
            },
        )
        project.messageBus.connect(project).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    currentRequest?.actionContext?.filesChanged(events)
                    contextChanged()
                }
            },
        )
    }

    private fun contextChanged() {
        SwingUtilities.invokeLater {
            if (!project.isDisposed && !refreshInProgress && currentRequest?.actionContext?.isCurrent() == false) {
                popupContent?.invalidateContext()
            }
        }
    }

    fun showFromCaret(editor: Editor) {
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        dismissHover(force = true)
        resolutionStatus.show(editor)
        val offset = editor.caretModel.offset
        val candidate =
            TaigaDocumentationHoverCandidate(
                file,
                editor,
                editor.offsetToXY(offset),
                offset,
                TaigaQuickDocumentationHoverKey(editor, offset, offset, editor.document.modificationStamp),
                explicit = true,
            )
        pendingKey = candidate.key
        resolutionJob = resolveCandidate(candidate)
    }

    private fun refreshCard(focusEditor: Boolean = false) {
        val previous = currentRequest?.takeUnless { refreshInProgress } ?: return
        val offset = refreshTarget?.offset()
        val file = PsiDocumentManager.getInstance(project).getPsiFile(previous.editor.document)
        if (offset == null || file == null) {
            popupContent?.showRefreshFailure()
            return
        }
        resolutionJob?.cancel()
        cancelScheduledHide()
        refreshInProgress = true
        popupContent?.showRefreshProgress(DumbService.isDumb(project))
        val candidate =
            TaigaDocumentationHoverCandidate(
                file,
                previous.editor,
                previous.anchor,
                offset,
                TaigaQuickDocumentationHoverKey(
                    previous.editor,
                    offset,
                    offset,
                    previous.editor.document.modificationStamp,
                ),
                explicit = true,
            )
        pendingKey = candidate.key
        resolutionJob = resolveCandidate(candidate, refreshing = true, focusEditor = focusEditor)
    }

    override fun dispose() {
        clearHover()
    }

    fun mouseMoved(event: EditorMouseEvent) {
        if (pinned || refreshInProgress || hasInteractiveCard()) {
            return
        }
        val candidate = event.toTaigaDocumentationHoverCandidate(project)

        if (candidate == null) {
            pointerLeftSource()
        } else if (candidate.key == pendingKey) {
            cancelScheduledHide()
            currentRequest?.takeIf { it.documentationRequest.shouldHighlight }?.let { request ->
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

    private fun resolveCandidate(
        candidate: TaigaDocumentationHoverCandidate,
        refreshing: Boolean = false,
        focusEditor: Boolean = false,
        target: TaigaDocumentationRefreshTarget? = refreshTarget.takeIf { refreshing },
    ): Job =
        coroutineScope.launch(Dispatchers.Default + CoroutineName("Taiga UI documentation PSI resolution")) {
            val request =
                ReadAction
                    .nonBlocking<TaigaQuickDocumentationHoverRequest?> {
                        val committed = if (refreshing) target?.let(candidate::withCommittedTarget) else candidate
                        if (project.isDisposed || !candidate.file.isValid) null else committed?.resolveRequest()
                    }.withDocumentsCommitted(project)
                    .inSmartMode(project)
                    .expireWith(project)
                    .expireWhen { !isActive || candidate.editor.isDisposed }
                    .executeSynchronously()
            withContext(Dispatchers.EDT) {
                if (refreshing && pendingKey == candidate.key) {
                    popupContent?.showRefreshProgress(false)
                }
            }
            val snapshot =
                request?.let {
                    val service = project.service<TaigaDocsService>()
                    if (refreshing) service.snapshotFor(it.sourceFile) else service.cachedSnapshotFor(it.sourceFile)
                }
            val resolved = request?.let { resolveDocumentation(it.documentationRequest, snapshot) }
            if (request != null && snapshot == null) project.service<TaigaDocsService>().warmUp(request.sourceFile)
            withContext(Dispatchers.EDT) {
                if (candidate.explicit) {
                    if (pendingKey == candidate.key) {
                        publishExplicitResolution(request, resolved, snapshot, refreshing, focusEditor)
                    }
                } else {
                    publishResolution(candidate, request, resolved, snapshot)
                }
            }
        }

    private fun publishResolution(
        candidate: TaigaDocumentationHoverCandidate,
        request: TaigaQuickDocumentationHoverRequest?,
        resolved: TaigaResolvedDocumentation?,
        snapshot: TaigaDocsSnapshot?,
    ) {
        if (pendingKey != candidate.key || pinned) return
        when {
            request == null -> {
                underline.clear()
                if (popup?.isVisible == true) scheduleHide() else clearHover()
            }
            !request.isStillCurrent(project) || (snapshot != null && resolved == null) -> clearHover()
            else -> handleRequest(request, resolved, candidate.key)
        }
    }

    private fun publishExplicitResolution(
        request: TaigaQuickDocumentationHoverRequest?,
        resolved: TaigaResolvedDocumentation?,
        snapshot: TaigaDocsSnapshot?,
        refreshing: Boolean,
        focusEditor: Boolean,
    ) {
        refreshInProgress = false
        if (request != null && resolved != null && request.isStillCurrent(project)) {
            resolutionStatus.hide()
            showExplicit(request, resolved, snapshot, refreshing, focusEditor)
        } else if (refreshing) {
            popupContent?.showRefreshFailure()
        } else {
            resolutionStatus.showUnavailable()
        }
    }

    private fun showExplicit(
        request: TaigaQuickDocumentationHoverRequest,
        resolved: TaigaResolvedDocumentation,
        snapshot: TaigaDocsSnapshot?,
        refreshing: Boolean,
        focusEditor: Boolean,
    ) {
        val view = captureCurrentView().takeIf { refreshing }?.refreshed(resolved, snapshot)
        val previous = if (refreshing) history.mapNotNull { it.refreshed(resolved, snapshot) } else emptyList()
        val location = popup?.takeIf { it.isVisible }?.locationOnScreen
        hidePopup(restoreNativeHover = false)
        cardEditors.dispose()
        history.clear()
        history.addAll(previous)
        currentRequest = request
        activeKey = request.key
        rememberRefreshTarget(request)
        showView(request, view ?: TaigaDocumentationView(resolved), requestFocus = !focusEditor)
        location?.let {
            popup?.setLocation(it)
            popup?.moveToFitScreen()
        }
        if (focusEditor) focusTaigaDocumentationEditor(project, request.editor)
    }

    private fun rememberRefreshTarget(request: TaigaQuickDocumentationHoverRequest) {
        refreshTarget?.dispose()
        refreshTarget = TaigaDocumentationRefreshTarget(request.editor.document, request.documentationRequest)
    }

    fun dismissHover(
        editor: Editor? = null,
        force: Boolean = false,
    ) {
        if (pinned && !force) return
        if (editor == null || editor.project == project) {
            cancelScheduledHide()
            clearHover()
        }
    }

    fun mouseExited(editor: Editor) {
        if (pinned || refreshInProgress || hasInteractiveCard()) {
            return
        }
        if (editor.project == project) pointerLeftSource()
    }

    private fun pointerLeftSource() {
        resolutionJob?.cancel()
        resolutionJob = null
        pendingKey = null
        hoverJob?.cancel()
        hoverJob = null

        // Keep the source highlight until the card closes, including the crossing to its popup.
        if (popup?.isVisible == true) {
            scheduleHide()
        } else {
            clearHover()
        }
    }

    private fun hasInteractiveCard(): Boolean = resolutionStatus.isVisible || popupContent?.hasTaigaFocus() == true

    private fun handleRequest(
        request: TaigaQuickDocumentationHoverRequest,
        cachedResolved: TaigaResolvedDocumentation?,
        candidateKey: TaigaQuickDocumentationHoverKey,
    ) {
        cancelScheduledHide()

        if (popup?.isVisible == true) {
            // A repeated event in the same attribute must not rebuild or reposition the card.
            hoverJob?.cancel()
            hoverJob = null
            if (request.key != activeKey) {
                // Keep the old popup visible until the next target has actually settled.
                hoverJob = scheduleHover(request, cachedResolved, candidateKey, switching = true)
            }
            return
        }

        currentRequest = request
        nativeHoverSuppression.suppress(request.editor)
        if (request.documentationRequest.shouldHighlight) {
            underline.show(
                request.editor,
                request.documentationRequest.startOffset,
                request.documentationRequest.endOffset,
            )
        } else {
            underline.clear()
        }

        if (request.key != activeKey) {
            rememberRefreshTarget(request)
            hoverJob?.cancel()
            hidePopup(restoreNativeHover = false)
            history.clear()
            currentView = null
            cardEditors.dispose()
            activeKey = request.key
            hoverJob = scheduleHover(request, cachedResolved, candidateKey)
        }
    }

    private fun scheduleHover(
        request: TaigaQuickDocumentationHoverRequest,
        cachedResolved: TaigaResolvedDocumentation?,
        candidateKey: TaigaQuickDocumentationHoverKey,
        switching: Boolean = false,
    ): Job =
        coroutineScope.launch(Dispatchers.Default + CoroutineName("Taiga UI quick documentation hover")) {
            delay(taigaDocumentationHoverDelay(switching))

            val resolved =
                cachedResolved
                    ?: project
                        .service<TaigaDocsService>()
                        .snapshotFor(request.sourceFile)
                        ?.resolve(request.documentationRequest)

            withContext(Dispatchers.EDT) {
                when {
                    pendingKey != candidateKey || !request.isStillCurrent(project) -> Unit
                    resolved == null -> clearHover()
                    else -> {
                        hoverJob = null
                        if (switching) {
                            // Keep the JBPopup visible; only its card content changes.
                            previewJob?.cancel()
                            previewJob = null
                            cardEditors.dispose()
                            history.clear()
                            currentView = null
                            currentRequest = request
                            activeKey = request.key
                            rememberRefreshTarget(request)
                        }
                        if (request.documentationRequest.shouldHighlight) {
                            underline.show(
                                request.editor,
                                request.documentationRequest.startOffset,
                                request.documentationRequest.endOffset,
                            )
                        } else {
                            underline.clear()
                        }
                        showView(request, TaigaDocumentationView(resolved))
                    }
                }
            }
        }

    private fun showView(
        request: TaigaQuickDocumentationHoverRequest,
        view: TaigaDocumentationView,
        requestFocus: Boolean = request.explicit || view.fullApi || view.expanded || history.isNotEmpty(),
    ) {
        val resolved = view.resolved
        val canShow =
            activeKey == request.key &&
                !project.isDisposed &&
                !request.editor.isDisposed

        if (canShow) {
            if (pinned) nativeHoverSuppression.restore() else nativeHoverSuppression.suppress(request.editor)
            val compactHover = view.isCompactHover(request.explicit)
            cardEditors.prepareForView(request, resolved, compactHover)
            currentView = view

            val panel =
                TaigaQuickDocumentationPopupPanel(
                    resolved = resolved,
                    onClose = { dismissHover(request.editor, force = true) },
                    actions = popupActions(request, resolved, view.showExample),
                    showExample = view.showExample,
                    pinned = pinned,
                    apiQuery = view.query.takeIf { view.fullApi },
                    selectedMember = view.selectedMember,
                    compactHover = compactHover,
                    hoverPointer = !request.explicit && !pinned,
                )
            val existingPopup = popup?.takeIf { it.isVisible && !pinned && !request.explicit }
            val existingHost = popupHost
            if (existingPopup != null && existingHost != null) {
                popupContent = panel
                existingHost.showCard(panel)
                existingPopup.setSize(existingHost.preferredSize)
                positionTaigaHoverCard(existingPopup, panel, request.documentationTargetScreenBounds())
                panel.restoreScrollPosition(view.scrollPosition)
                request.completePresentation(panel, refreshInProgress)
                loadIconPreviews(request, resolved, panel)
                return
            }

            val host = TaigaDocumentationPopupHost(panel)
            val createdPopup =
                createTaigaDocumentationPopup(
                    project,
                    host,
                    panel.preferredFocus,
                    requestFocus,
                    request.explicit,
                    pinned,
                ) {
                    currentRequest?.documentationTargetScreenBounds() ?: request.documentationTargetScreenBounds()
                }

            createdPopup.addListener(
                object : JBPopupListener {
                    override fun onClosed(event: LightweightWindowEvent) {
                        if (popup === createdPopup) {
                            pinned = false
                            pinnedSize = null
                            pinnedLocation = null
                            refreshInProgress = false
                            resolutionJob?.cancel()
                            cardEditors.dispose()
                            history.clear()
                            currentView = null
                            popup = null
                            popupContent = null
                            popupHost?.stopTransition()
                            popupHost = null
                            activeKey = null
                            pendingKey = null
                            currentRequest = null
                            refreshTarget?.dispose()
                            refreshTarget = null
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
            popupHost = host
            createdPopup.showTaigaDocumentationPopup(
                panel,
                request.editor,
                request.documentationTargetScreenBounds(),
                request.popupPlacement(panel.preferredSize),
                pinnedSize,
                pinnedLocation,
                pinned,
                request.explicit,
            )
            panel.restoreScrollPosition(view.scrollPosition)
            request.completePresentation(panel, refreshInProgress)
            loadIconPreviews(request, resolved, panel)
        }
    }

    private fun popupActions(
        request: TaigaQuickDocumentationHoverRequest,
        resolved: TaigaResolvedDocumentation,
        exampleVisible: Boolean,
    ): TaigaDocumentationPopupActions =
        TaigaDocumentationPopupActions(
            navigateToSource =
                resolved.source?.let { source ->
                    {
                        dismissHover(request.editor, force = true)
                        LocalFileSystem.getInstance().findFileByNioFile(source.file)?.let { file ->
                            OpenFileDescriptor(project, file, source.offset).navigate(true)
                        }
                    }
                },
            togglePin = { togglePin(request, resolved, exampleVisible) },
            openFullCard = {
                val expanded = (captureCurrentView() ?: TaigaDocumentationView(resolved)).copy(expanded = true)
                hidePopup(restoreNativeHover = false)
                showView(request, expanded, requestFocus = true)
            },
            applyValue =
                cardEditors.binding?.let { editor ->
                    { value: String ->
                        applyCardChange(request) { editor.apply(value) }
                    }
                },
            currentValue = cardEditors.binding?.currentValue,
            chooseIcon = { reference -> chooseIcon(request, reference) },
            openMember = { member ->
                navigate(request, TaigaDocumentationView(member))
            },
            showExample = {
                val view = captureCurrentView() ?: TaigaDocumentationView(resolved)
                hidePopup(restoreNativeHover = false)
                showView(request, view.copy(showExample = true))
            },
            openOwner = { entity -> navigate(request, TaigaDocumentationView(entity, fullApi = true)) },
            goBack = if (history.isEmpty()) null else ({ goBack(request) }),
            queryChanged = { query -> currentView = currentView?.copy(query = query) },
            selectionChanged = { selected -> currentView = currentView?.copy(selectedMember = selected) },
            showImportFixes = {
                val offset = refreshTarget?.offset()
                dismissHover(request.editor, force = true)
                if (offset != null) showTaigaAngularQuickFixes(project, request.editor, offset)
            },
            navigateDeclaration = { source ->
                dismissHover(request.editor, force = true)
                LocalFileSystem.getInstance().findFileByNioFile(source.file)?.let { file ->
                    OpenFileDescriptor(project, file, source.offset).navigate(true)
                }
            },
            applyTemplateEdit = templateEditAction(request, resolved),
            refresh = { refreshCard() },
            resize = ::resizePopup,
        )

    private fun templateEditAction(
        request: TaigaQuickDocumentationHoverRequest,
        resolved: TaigaResolvedDocumentation,
    ): ((TaigaDocumentationTemplateEdit) -> String)? =
        cardEditors.template?.let { adapter ->
            { edit ->
                applyCardChange(request, focusEditor = edit.kind == TaigaTemplateEditKind.ADD_REQUIRED) {
                    val result =
                        if (edit in resolved.templateEdits()) adapter.apply(edit) else "Reopen the card before applying"
                    if (result.startsWith("Replaced")) edit.replacement?.let { refreshTarget?.name = it }
                    result
                }
            }
        }

    private fun resizePopup() {
        val panel = popupContent
        val popup = popup
        if (panel == null || popup == null) return
        if (pinned) {
            popup.moveToFitScreen()
        } else {
            popup.setSize(panel.preferredSize)
            val request = currentRequest
            if (request != null && !request.explicit && popup.isVisible) {
                positionTaigaHoverCard(popup, panel, request.documentationTargetScreenBounds())
            } else {
                popup.moveToFitScreen()
            }
        }
    }

    private fun applyCardChange(
        request: TaigaQuickDocumentationHoverRequest,
        focusEditor: Boolean = false,
        apply: () -> String,
    ): String {
        if (!request.actionContext.isCurrent()) {
            popupContent?.invalidateContext()
            return STALE_DOCUMENTATION_MESSAGE
        }
        val result = apply()
        if (result.startsWith("Applied") || result.startsWith("Added") || result.startsWith("Replaced")) {
            popupContent?.invalidateContext()
            if (focusEditor) focusTaigaDocumentationEditor(project, request.editor)
            refreshCard(focusEditor)
        }
        return result
    }

    private fun togglePin(
        request: TaigaQuickDocumentationHoverRequest,
        resolved: TaigaResolvedDocumentation,
        exampleVisible: Boolean,
    ) {
        val location = popup?.takeIf { it.isVisible }?.locationOnScreen
        val view = captureCurrentView() ?: TaigaDocumentationView(resolved, exampleVisible)
        pinned = !pinned
        cancelScheduledHide()
        if (!refreshInProgress) resolutionJob?.cancel()
        hoverJob?.cancel()
        underline.clear()
        if (!pinned && !refreshInProgress && !request.isStillCurrent(project)) {
            dismissHover(request.editor, force = true)
        } else {
            hidePopup(restoreNativeHover = false)
            if (!pinned) {
                pinnedSize = null
                pinnedLocation = null
            }
            showView(request, view)
            location?.let {
                popup?.setLocation(it)
                popup?.moveToFitScreen()
            }
        }
    }

    private fun goBack(request: TaigaQuickDocumentationHoverRequest) {
        val view = history.removeLastOrNull() ?: return
        hidePopup(restoreNativeHover = false)
        showView(request, view)
    }

    private fun navigate(
        request: TaigaQuickDocumentationHoverRequest,
        view: TaigaDocumentationView,
    ) {
        captureCurrentView()?.let(history::addLast)
        hidePopup(restoreNativeHover = false)
        showView(request, view.copy(expanded = true))
    }

    private fun captureCurrentView(): TaigaDocumentationView? =
        currentView?.let { it.copy(scrollPosition = popupContent?.scrollPosition ?: it.scrollPosition) }

    private fun loadIconPreviews(
        request: TaigaQuickDocumentationHoverRequest,
        resolved: TaigaResolvedDocumentation,
        panel: TaigaQuickDocumentationPopupPanel,
    ) {
        val references = resolved.documentationIcons.filter { it.name.isNotEmpty() }.take(2)
        if (references.isEmpty()) return

        previewJob =
            icons.loadPreviews(request.sourceFile, references) { previews ->
                if (popupContent === panel && request.isStillCurrent(project)) {
                    panel.showIconPreviews(previews)
                    resizePopup()
                }
            }
    }

    private fun chooseIcon(
        request: TaigaQuickDocumentationHoverRequest,
        reference: TaigaDocumentationIcon,
    ) {
        if (!request.actionContext.isCurrent()) {
            popupContent?.invalidateContext()
            return
        }
        dismissHover(request.editor, force = true)
        icons.showChooser(request.editor, request.sourceFile, reference) { request.isStillCurrent(project) }
    }

    private fun scheduleHide() {
        if (pinned || refreshInProgress) return
        if (popup?.isVisible == true) {
            hideJob?.cancel()
            hideJob =
                coroutineScope.launch(Dispatchers.EDT + CoroutineName("Taiga UI quick documentation hover hide")) {
                    delay(HOVER_HIDE_GRACE_PERIOD)
                    hideJob = null

                    if (popupContent?.containsTaigaPointer() != true && popupContent?.hasTaigaFocus() != true) {
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
        pinned = false
        pinnedSize = null
        pinnedLocation = null
        refreshInProgress = false
        resolutionStatus.hide()
        refreshTarget?.dispose()
        refreshTarget = null
        cardEditors.dispose()
        currentView = null
        history.clear()
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
        if (pinned && currentPopup?.isVisible == true) {
            pinnedSize = Dimension(currentPopup.size)
            pinnedLocation = Point(currentPopup.locationOnScreen)
        }

        popup = null
        popupContent = null
        popupHost?.stopTransition()
        popupHost = null
        currentPopup?.cancel()

        if (restoreNativeHover) {
            nativeHoverSuppression.restore()
        }
    }
}

private fun TaigaDocumentationCardEditors.prepareForView(
    request: TaigaQuickDocumentationHoverRequest,
    resolved: TaigaResolvedDocumentation,
    compactHover: Boolean,
) {
    if (compactHover) {
        dispose()
    } else {
        val project = request.editor.project ?: return
        prepare(
            request.editor,
            resolved,
            request.actionContext,
            request.isStillCurrent(project),
        )
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
            actionContext = TaigaDocumentationActionContext(file.project, editor.document),
            explicit = explicit,
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
    val explicit: Boolean = false,
)

/** Resolve the tracked refresh target against the committed document after indexing/typing settles. */
private fun TaigaDocumentationHoverCandidate.withCommittedTarget(
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

private fun TaigaQuickDocumentationHoverRequest.isStillCurrent(project: Project): Boolean {
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

private fun TaigaQuickDocumentationHoverRequest.completePresentation(
    panel: TaigaQuickDocumentationPopupPanel,
    refreshing: Boolean,
) {
    if (refreshing) {
        panel.showRefreshProgress(editor.project?.let(DumbService::isDumb) == true)
    } else if (!actionContext.isCurrent()) {
        panel.invalidateContext()
    }
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

private data class TaigaQuickDocumentationHoverKey(
    val editor: Editor,
    val startOffset: Int,
    val endOffset: Int,
    val modificationStamp: Long,
)

private val TaigaDocumentationRequest.shouldHighlight: Boolean
    get() =
        this is TaigaDocumentationRequest.Member ||
            (this is TaigaDocumentationRequest.Entity && subjects.any { subject -> subject.selector != null })

private val HOVER_HIDE_GRACE_PERIOD = 750.milliseconds
