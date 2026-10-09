package org.taigaui.designtokens.documentation

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project

/** Keeps range-marker adapters tied to the card's immutable API and captured action context. */
internal class TaigaDocumentationCardEditors(
    private val project: Project,
) : Disposable {
    var binding: TaigaDocumentationBindingEditor? = null
        private set
    var template: TaigaDocumentationTemplateEditor? = null
        private set
    private var member: TaigaResolvedDocumentation.Member? = null
    private var element: TaigaDocumentationElement? = null

    fun prepare(
        editor: Editor,
        resolved: TaigaResolvedDocumentation,
        context: TaigaDocumentationActionContext,
        isCurrent: Boolean,
    ) {
        prepareBinding(editor, resolved as? TaigaResolvedDocumentation.Member, context, isCurrent)
        prepareTemplate(editor, resolved.templateElement, context, isCurrent)
    }

    private fun prepareTemplate(
        editor: Editor,
        target: TaigaDocumentationElement?,
        context: TaigaDocumentationActionContext,
        isCurrent: Boolean,
    ) {
        if (target == element) return
        disposeTemplate()
        if (target == null) return
        val document = editor.document
        val unchanged =
            target.endOffset <= document.textLength &&
                document.charsSequence.subSequence(target.startOffset, target.endOffset).toString() == target.text
        if (unchanged && isCurrent && document.isWritable) {
            element = target
            template =
                TaigaDocumentationTemplateEditor(
                    project,
                    document,
                    target,
                    isContextCurrent = context::isCurrent,
                ) { editor.caretModel.moveToOffset(it) }
        }
    }

    @Suppress("ReturnCount")
    private fun prepareBinding(
        editor: Editor,
        target: TaigaResolvedDocumentation.Member?,
        context: TaigaDocumentationActionContext,
        isCurrent: Boolean,
    ) {
        if (target == member) return
        disposeBinding()
        val input = target?.binding ?: return
        val values = target.localValues()
        if (target.kind != TaigaApiMemberKind.INPUT || input.literal == null || values.isEmpty()) return
        val document = editor.document
        if (!isCurrent || !document.isWritable) return
        if (input.endOffset > document.textLength ||
            input.context.any { it.endOffset > document.textLength } ||
            document.charsSequence.subSequence(input.startOffset, input.endOffset).toString() != input.text
        ) {
            return
        }
        member = target
        binding = TaigaDocumentationBindingEditor(project, document, input, values, context::isCurrent)
    }

    override fun dispose() {
        disposeBinding()
        disposeTemplate()
    }

    private fun disposeBinding() {
        binding?.dispose()
        binding = null
        member = null
    }

    private fun disposeTemplate() {
        template?.dispose()
        template = null
        element = null
    }
}
