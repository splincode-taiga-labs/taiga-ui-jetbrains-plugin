package org.taigaui.designtokens.documentation

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
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
import javax.swing.SwingUtilities

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
    private val hoverResolver = TaigaDocumentationHoverResolver(project)
    private var resolutionJob: Job? = null
    private var pendingKey: TaigaQuickDocumentationHoverKey? = null
    private var currentRequest: TaigaQuickDocumentationHoverRequest? = null

    private var pinned = false
    private val codeActions =
        TaigaDocumentationCodeActions(
            project,
            onInvalidate = { popupContent?.invalidateContext() },
            onRefresh = ::refreshCard,
            onRenamed = { refreshTarget?.name = it },
        )
    private val navigation = TaigaDocumentationNavigationState()
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
            val request = hoverResolver.resolveRequest(candidate, refreshing, target) { !isActive }
            withContext(Dispatchers.EDT) {
                if (refreshing && pendingKey == candidate.key) {
                    popupContent?.showRefreshProgress(false)
                }
            }
            val (snapshot, resolved) = hoverResolver.loadDocumentation(request, refreshing)
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
        val view = navigation.capture(popupContent?.scrollPosition).takeIf { refreshing }?.refreshed(resolved, snapshot)
        val previous = if (refreshing) navigation.refreshedHistory(resolved, snapshot) else emptyList()
        val location = popup?.takeIf { it.isVisible }?.locationOnScreen
        hidePopup(restoreNativeHover = false)
        codeActions.dispose()
        navigation.replaceHistory(previous)
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
            navigation.clear()
            codeActions.dispose()
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
                            codeActions.dispose()
                            navigation.clear()
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
        requestFocus: Boolean = request.explicit || view.fullApi || view.expanded || navigation.canGoBack,
    ) {
        val resolved = view.resolved
        val canShow =
            activeKey == request.key &&
                !project.isDisposed &&
                !request.editor.isDisposed

        if (canShow) {
            if (pinned) nativeHoverSuppression.restore() else nativeHoverSuppression.suppress(request.editor)
            val compactHover = view.isCompactHover(request.explicit)
            codeActions.prepareForView(request, resolved, compactHover)
            navigation.currentView = view

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
            if (!reuseHoverPopup(request, panel)) {
                val host = TaigaDocumentationPopupHost(panel, stabilizesHoverSize = !request.explicit && !pinned)
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
                registerPopupCleanup(createdPopup)
                popup = createdPopup
                popupContent = panel
                popupHost = host
                createdPopup.showTaigaDocumentationPopup(
                    panel,
                    request.editor,
                    request.documentationTargetScreenBounds(),
                    request.popupPlacement(host.preferredSize),
                    pinnedSize,
                    pinnedLocation,
                    pinned,
                    request.explicit,
                )
            }
            panel.restoreScrollPosition(view.scrollPosition)
            request.completePresentation(panel, refreshInProgress)
            loadIconPreviews(request, resolved, panel)
        }
    }

    private fun reuseHoverPopup(
        request: TaigaQuickDocumentationHoverRequest,
        panel: TaigaQuickDocumentationPopupPanel,
    ): Boolean {
        val existingPopup = popup?.takeIf { it.isVisible && !pinned && !request.explicit }
        val host = popupHost
        if (existingPopup != null && host != null) {
            val modeChanged = host.card.compactHover != panel.compactHover
            popupContent = panel
            host.showCard(panel)
            if (modeChanged || !host.stabilizesHoverSize || !panel.compactHover) {
                existingPopup.setSize(host.preferredSize)
            }
            positionTaigaHoverCard(existingPopup, panel, request.documentationTargetScreenBounds())
            return true
        }
        return false
    }

    private fun registerPopupCleanup(createdPopup: JBPopup) {
        createdPopup.addListener(
            object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    if (popup === createdPopup) {
                        pinned = false
                        pinnedSize = null
                        pinnedLocation = null
                        refreshInProgress = false
                        resolutionJob?.cancel()
                        codeActions.dispose()
                        navigation.clear()
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
                val current = navigation.capture(popupContent?.scrollPosition) ?: TaigaDocumentationView(resolved)
                val expanded = current.copy(expanded = true)
                hidePopup(restoreNativeHover = false)
                showView(request, expanded, requestFocus = true)
            },
            applyValue =
                codeActions.binding?.let { editor ->
                    { value: String -> codeActions.applyBinding(request, editor, value) }
                },
            currentValue = codeActions.binding?.currentValue,
            chooseIcon = { reference -> chooseIcon(request, reference) },
            openMember = { member ->
                navigate(request, TaigaDocumentationView(member))
            },
            showExample = {
                val view = navigation.capture(popupContent?.scrollPosition) ?: TaigaDocumentationView(resolved)
                hidePopup(restoreNativeHover = false)
                showView(request, view.copy(showExample = true))
            },
            openOwner = { entity -> navigate(request, TaigaDocumentationView(entity, fullApi = true)) },
            goBack = if (!navigation.canGoBack) null else ({ goBack(request) }),
            queryChanged = navigation::setQuery,
            selectionChanged = navigation::setSelection,
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
            applyTemplateEdit = codeActions.templateEditAction(request, resolved),
            refresh = { refreshCard() },
            resize = ::resizePopup,
        )

    private fun resizePopup() {
        val panel = popupContent
        val popup = popup
        if (panel == null || popup == null) return
        if (pinned) {
            popup.moveToFitScreen()
        } else {
            if (popupHost?.stabilizesHoverSize != true || !panel.compactHover) {
                popup.setSize(popupHost?.preferredSize ?: panel.preferredSize)
            }
            val request = currentRequest
            if (request != null && !request.explicit && popup.isVisible) {
                positionTaigaHoverCard(popup, panel, request.documentationTargetScreenBounds())
            } else {
                popup.moveToFitScreen()
            }
        }
    }

    private fun togglePin(
        request: TaigaQuickDocumentationHoverRequest,
        resolved: TaigaResolvedDocumentation,
        exampleVisible: Boolean,
    ) {
        val location = popup?.takeIf { it.isVisible }?.locationOnScreen
        val view = navigation.capture(popupContent?.scrollPosition) ?: TaigaDocumentationView(resolved, exampleVisible)
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
        val view = navigation.back() ?: return
        hidePopup(restoreNativeHover = false)
        showView(request, view)
    }

    private fun navigate(
        request: TaigaQuickDocumentationHoverRequest,
        view: TaigaDocumentationView,
    ) {
        val next = navigation.visit(view, popupContent?.scrollPosition)
        hidePopup(restoreNativeHover = false)
        showView(request, next)
    }

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
        codeActions.dispose()
        navigation.clear()
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
