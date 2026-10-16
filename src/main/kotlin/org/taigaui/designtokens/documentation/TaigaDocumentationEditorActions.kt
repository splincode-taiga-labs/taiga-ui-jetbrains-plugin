package org.taigaui.designtokens.documentation

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import javax.swing.SwingUtilities

/** Delegate import selection and component/module scope edits to the IDE's Angular intentions. */
internal fun showTaigaAngularQuickFixes(
    project: Project,
    editor: Editor,
    offset: Int = editor.caretModel.offset,
) {
    if (project.isDisposed || editor.isDisposed) return
    if (DumbService.isDumb(project)) {
        HintManager.getInstance().showInformationHint(editor, "Angular quick fixes are available after indexing.")
        return
    }
    val manager = ActionManager.getInstance()
    val action = manager.getAction("ShowIntentionActions") ?: return
    editor.caretModel.moveToOffset(offset.coerceIn(0, editor.document.textLength))
    editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
    focusTaigaDocumentationEditor(project, editor)
    SwingUtilities.invokeLater {
        if (!project.isDisposed && !editor.isDisposed) {
            manager.tryToExecute(action, null, editor.contentComponent, ActionPlaces.EDITOR_POPUP, true)
        }
    }
}

internal fun focusTaigaDocumentationEditor(
    project: Project,
    editor: Editor,
) {
    if (!project.isDisposed && !editor.isDisposed) {
        IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
    }
}
