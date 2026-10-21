package org.taigaui.designtokens.documentation

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent

/** Owns the temporary explicit-request popup separately from an installed documentation card. */
internal class TaigaDocumentationStatusPopup(
    private val project: Project,
    private val onClose: () -> Unit,
) {
    private var popup: JBPopup? = null
    private var panel: TaigaDocumentationStatusPanel? = null
    private var target: RangeMarker? = null
    var editor: Editor? = null
        private set

    val isVisible: Boolean get() = popup?.isVisible == true

    fun show(editor: Editor) {
        hide()
        val offset = editor.caretModel.offset
        val marker = editor.document.createRangeMarker(offset, offset)
        target = marker
        val content =
            TaigaDocumentationStatusPanel(
                if (DumbService.isDumb(project)) "Waiting for indexing…" else "Loading Taiga UI API…",
                onClose = onClose,
                showQuickFixes = {
                    val position = marker.takeIf { it.isValid }?.startOffset
                    onClose()
                    if (position != null) showTaigaAngularQuickFixes(project, editor, position)
                },
            )
        val created =
            JBPopupFactory
                .getInstance()
                .createComponentPopupBuilder(content, content.closeButton)
                .setProject(project)
                .setFocusable(true)
                .setRequestFocus(true)
                .createPopup()
        popup = created
        panel = content
        this.editor = editor
        created.addListener(
            object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    if (popup === created) onClose()
                }
            },
        )
        created.showInBestPositionFor(editor)
    }

    fun showLoading() {
        panel?.showLoading()
    }

    fun showUnavailable() {
        panel?.showUnavailable()
        popup?.pack(true, true)
    }

    fun hide() {
        val previous = popup
        popup = null
        panel = null
        editor = null
        target?.dispose()
        target = null
        previous?.cancel()
    }
}
