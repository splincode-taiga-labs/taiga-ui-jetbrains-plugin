package org.taigaui.designtokens.completion

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.codeInsight.lookup.LookupListener
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.LookupManagerListener
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.ui.HintHint
import com.intellij.ui.LightweightHint
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.taigaui.designtokens.documentation.DesignTokenHoverPopupModel
import org.taigaui.designtokens.project.DesignTokenIndexService
import org.taigaui.designtokens.settings.TaigaDesignTokensSettings
import java.awt.Dimension
import java.awt.Point
import java.nio.file.Path
import javax.swing.JLayeredPane

@Service(Service.Level.PROJECT)
internal class DesignTokenCompletionPreviewController(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    private var activeLookup: Lookup? = null
    private var activeListener: LookupListener? = null
    private var previewHint: LightweightHint? = null
    private var previewPanel: DesignTokenCompletionPreviewPanel? = null
    private var previewJob: Job? = null
    private var previewKey: PreviewKey? = null
    private var previewAnchorY: Int? = null

    init {
        project.messageBus
            .connect()
            .subscribe(
                LookupManagerListener.TOPIC,
                object : LookupManagerListener {
                    override fun activeLookupChanged(
                        oldLookup: Lookup?,
                        newLookup: Lookup?,
                    ) {
                        attach(newLookup)
                    }
                },
            )
    }

    fun ensureAttached() {
        coroutineScope.launch(Dispatchers.EDT) {
            attach(LookupManager.getInstance(project).activeLookup)
        }
    }

    private fun attach(lookup: Lookup?) {
        if (activeLookup === lookup) {
            lookup?.let(::requestPreview)
            return
        }

        detach()

        if (
            lookup?.isCompletion != true ||
            !service<TaigaDesignTokensSettings>().showCompletionPreview
        ) {
            return
        }

        val listener =
            object : LookupListener {
                override fun lookupShown(event: LookupEvent) {
                    requestPreview(lookup)
                }

                override fun currentItemChanged(event: LookupEvent) {
                    requestPreview(lookup)
                }

                override fun uiRefreshed() {
                    requestPreview(lookup)
                }

                override fun itemSelected(event: LookupEvent) {
                    hidePreview()
                }

                override fun lookupCanceled(event: LookupEvent) {
                    hidePreview()
                }
            }

        activeLookup = lookup
        activeListener = listener
        lookup.addLookupListener(listener)
        requestPreview(lookup)
    }

    private fun detach() {
        val lookup = activeLookup
        val listener = activeListener

        if (lookup != null && listener != null) {
            lookup.removeLookupListener(listener)
        }

        activeLookup = null
        activeListener = null
        previewJob?.cancel()
        previewJob = null
        previewKey = null
        previewAnchorY = null
        hidePreview()
    }

    private fun requestPreview(lookup: Lookup) {
        val candidate =
            lookup
                .previewCandidate()
                ?.takeIf { service<TaigaDesignTokensSettings>().showCompletionPreview }

        if (candidate == null) {
            clearPreviewRequest()
        } else {
            val key =
                PreviewKey(
                    lookup = lookup,
                    tokenName = candidate.tokenName,
                    sourceFile = candidate.sourceFile,
                    lookupElement = candidate.lookupElement,
                )

            if (previewKey != key || previewJob?.isActive != true) {
                previewKey = key
                previewJob?.cancel()

                if (previewHint?.isVisible != true) {
                    showLoading(lookup, candidate.tokenName)
                }

                previewJob =
                    coroutineScope.launch(CoroutineName("Taiga UI design token completion preview")) {
                        resolveAndShowPreview(
                            lookup = lookup,
                            candidate = candidate,
                            key = key,
                        )
                    }
            }
        }
    }

    private suspend fun resolveAndShowPreview(
        lookup: Lookup,
        candidate: CompletionPreviewCandidate,
        key: PreviewKey,
    ) {
        val hasDesignTokenContext =
            readAction {
                candidate.editor
                    .designTokenCompletionContextAt(candidate.caretOffset)
                    ?.prefix
                    ?.startsWith(TAIGA_TOKEN_ROOT) == true
            }

        if (hasDesignTokenContext) {
            val indexService = project.service<DesignTokenIndexService>()
            val groups =
                withContext(Dispatchers.Default) {
                    indexService.resolveToken(candidate.sourceFile, candidate.tokenName)
                }
            val model =
                groups
                    .takeIf { values -> values.isNotEmpty() }
                    ?.let { values ->
                        DesignTokenHoverPopupModel.create(
                            tokenName = candidate.tokenName,
                            groups = values,
                        )
                    }
                    ?: readAction {
                        candidate.lookupElement.toCustomPropertyPreviewModel(
                            tokenName = candidate.tokenName,
                            project = project,
                        )
                    }

            withContext(Dispatchers.EDT) {
                if (
                    previewKey == key &&
                    activeLookup === lookup &&
                    lookup.currentItem === candidate.lookupElement
                ) {
                    if (model == null) {
                        hidePreview()
                    } else {
                        showModel(lookup, model)
                    }
                }
            }
        } else {
            withContext(Dispatchers.EDT) {
                if (previewKey == key) {
                    clearPreviewRequest()
                }
            }
        }
    }

    private fun clearPreviewRequest() {
        previewKey = null
        previewJob?.cancel()
        previewJob = null
        hidePreview()
    }

    private fun showLoading(
        lookup: Lookup,
        tokenName: String,
    ) {
        val panel = previewPanel ?: DesignTokenCompletionPreviewPanel().also { previewPanel = it }

        panel.showLoading(tokenName)
        showOrMoveHint(lookup, panel.preferredSize)
    }

    private fun showModel(
        lookup: Lookup,
        model: DesignTokenHoverPopupModel,
    ) {
        val panel = previewPanel ?: DesignTokenCompletionPreviewPanel().also { previewPanel = it }

        panel.showModel(model)
        showOrMoveHint(lookup, panel.preferredSize)
    }

    private fun showOrMoveHint(
        lookup: Lookup,
        previewSize: Dimension,
    ) {
        val editor = lookup.topLevelEditor
        val layeredPane = editor.contentComponent.rootPane?.layeredPane ?: return
        val location =
            previewLocation(
                lookup = lookup,
                previewSize = previewSize,
                layeredPane = layeredPane,
                anchorY = previewAnchorY,
            )
        previewAnchorY = location.y
        val existingHint = previewHint
        val hint =
            existingHint
                ?: LightweightHint(requireNotNull(previewPanel))
                    .apply {
                        setForceLightweightPopup(true)
                        setCancelOnClickOutside(false)
                        setBelongsToGlobalPopupStack(false)
                        setCancelOnOtherWindowOpen(false)
                    }.also { previewHint = it }

        if (existingHint != null) {
            hint.pack()
            hint.updateLocation(location.x, location.y)
        }

        if (!hint.isVisible) {
            hint.show(
                layeredPane,
                location.x,
                location.y,
                editor.contentComponent,
                HintHint().setRequestFocus(false),
            )
        }
    }

    private fun hidePreview() {
        previewHint?.takeIf { hint -> hint.isVisible }?.hide()
    }
}

private fun Lookup.previewCandidate(): CompletionPreviewCandidate? {
    val lookupElement = currentItem
    val tokenName = lookupElement?.lookupString?.let(::normalizeDesignTokenLookupString)
    val sourceFile = sourceFilePath()
    val editor = topLevelEditor

    return if (lookupElement != null && tokenName != null && sourceFile != null) {
        CompletionPreviewCandidate(
            tokenName = tokenName,
            sourceFile = sourceFile,
            lookupElement = lookupElement,
            editor = editor,
            caretOffset = editor.caretModel.offset,
        )
    } else {
        null
    }
}

private fun Lookup.sourceFilePath(): Path? {
    val documentPath =
        FileDocumentManager
            .getInstance()
            .getFile(topLevelEditor.document)
            ?.path
    val psiPath = psiFile?.virtualFile?.path

    return pathOrNull(documentPath ?: psiPath ?: return null)
}

internal fun normalizeDesignTokenLookupString(value: String): String? =
    when {
        value.startsWith(TAIGA_TOKEN_ROOT) -> value
        value.startsWith(TAIGA_TOKEN_BARE_ROOT) -> "--$value"
        else -> null
    }

private fun previewLocation(
    lookup: Lookup,
    previewSize: Dimension,
    layeredPane: JLayeredPane,
    anchorY: Int?,
): Point {
    val lookupBounds = lookup.bounds
    val gap = JBUI.scale(PREVIEW_GAP)
    val rightX = lookupBounds.x + lookupBounds.width + gap
    val leftX = lookupBounds.x - previewSize.width - gap
    val fitsRight = rightX + previewSize.width <= layeredPane.width
    val x = if (fitsRight) rightX else leftX.coerceAtLeast(0)
    val maxY = (layeredPane.height - JBUI.scale(MAX_PREVIEW_HEIGHT)).coerceAtLeast(0)
    val y = anchorY ?: lookupBounds.y.coerceIn(0, maxY)

    return Point(x, y)
}

private data class CompletionPreviewCandidate(
    val tokenName: String,
    val sourceFile: Path,
    val lookupElement: LookupElement,
    val editor: com.intellij.openapi.editor.Editor,
    val caretOffset: Int,
)

private data class PreviewKey(
    val lookup: Lookup,
    val tokenName: String,
    val sourceFile: Path,
    val lookupElement: LookupElement,
)

private const val TAIGA_TOKEN_ROOT = "--tui-"
private const val TAIGA_TOKEN_BARE_ROOT = "tui-"
private const val PREVIEW_GAP = 8
