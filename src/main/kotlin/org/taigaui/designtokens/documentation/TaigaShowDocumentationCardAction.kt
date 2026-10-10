package org.taigaui.designtokens.documentation

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction

internal class TaigaShowDocumentationCardAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val file = event.getData(CommonDataKeys.PSI_FILE)
        event.presentation.isEnabled = project != null &&
            event.getData(CommonDataKeys.EDITOR) != null &&
            file != null &&
            (file.isTaigaTemplateFile() || file.name.endsWith(".ts"))
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        project.service<TaigaQuickDocumentationHoverController>().showFromCaret(editor)
    }
}
