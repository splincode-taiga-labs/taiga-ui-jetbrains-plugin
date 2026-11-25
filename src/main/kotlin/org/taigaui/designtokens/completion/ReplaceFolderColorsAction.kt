package org.taigaui.designtokens.completion

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import org.taigaui.designtokens.project.selectedProjectDirectory

internal class ReplaceFolderColorsAction(
    private val confirm: (Project, List<FolderColorPreview>) -> Boolean = { project, previews ->
        FolderColorPreviewDialog(project, previews).showAndGet()
    },
) : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.selectedProjectDirectory() != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val directory = event.selectedProjectDirectory() ?: return

        object : Task.Backgroundable(project, FOLDER_COLOR_REPLACEMENT_TITLE, true) {
            private lateinit var replacement: FolderColorReplacement

            override fun run(indicator: ProgressIndicator) {
                replacement = FolderColorReplacement.scan(project, directory, indicator)
            }

            override fun onSuccess() {
                if (project.isDisposed) return

                val previews = replacement.previews
                val shouldApply = previews.isEmpty() || confirm(project, previews)

                if (!shouldApply || project.isDisposed) return

                val message = replacement.apply(project)

                WindowManager.getInstance().getStatusBar(project)?.setInfo(message)
            }
        }.queue()
    }
}

internal class TaigaProjectViewActions : DefaultActionGroup() {
    init {
        templatePresentation.isHideGroupIfEmpty = true
    }
}
