package org.taigaui.designtokens.documentation

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project

/**
 * Owns source-changing card operations. Read-only documentation never authorizes
 * a write; each editor adapter rechecks its captured installed-API context.
 */
internal class TaigaDocumentationCodeActions(
    private val project: Project,
    private val onInvalidate: () -> Unit,
    private val onRefresh: (Boolean) -> Unit,
    private val onRenamed: (String) -> Unit,
) : Disposable {
    private val editors = TaigaDocumentationCardEditors(project)

    val binding: TaigaDocumentationBindingEditor?
        get() = editors.binding

    fun prepareForView(
        request: TaigaQuickDocumentationHoverRequest,
        resolved: TaigaResolvedDocumentation,
        compactHover: Boolean,
    ) {
        if (compactHover) {
            editors.dispose()
        } else {
            val project = request.editor.project ?: return
            editors.prepare(
                request.editor,
                resolved,
                request.actionContext,
                request.isStillCurrent(project),
            )
        }
    }

    fun applyBinding(
        request: TaigaQuickDocumentationHoverRequest,
        editor: TaigaDocumentationBindingEditor,
        value: String,
    ): String = applyCardChange(request) { editor.apply(value) }

    fun templateEditAction(
        request: TaigaQuickDocumentationHoverRequest,
        resolved: TaigaResolvedDocumentation,
    ): ((TaigaDocumentationTemplateEdit) -> String)? =
        editors.template?.let { adapter ->
            { edit ->
                applyCardChange(request, focusEditor = edit.kind == TaigaTemplateEditKind.ADD_REQUIRED) {
                    val result =
                        if (edit in resolved.templateEdits()) adapter.apply(edit) else "Reopen the card before applying"
                    if (result.startsWith("Replaced")) edit.replacement?.let(onRenamed)
                    result
                }
            }
        }

    private fun applyCardChange(
        request: TaigaQuickDocumentationHoverRequest,
        focusEditor: Boolean = false,
        apply: () -> String,
    ): String {
        if (!request.actionContext.isCurrent()) {
            onInvalidate()
            return STALE_DOCUMENTATION_MESSAGE
        }
        val result = apply()
        if (result.startsWith("Applied") || result.startsWith("Added") || result.startsWith("Replaced")) {
            onInvalidate()
            if (focusEditor) focusTaigaDocumentationEditor(project, request.editor)
            onRefresh(focusEditor)
        }
        return result
    }

    override fun dispose() = editors.dispose()
}
