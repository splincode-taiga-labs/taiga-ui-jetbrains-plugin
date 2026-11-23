package org.taigaui.designtokens.completion

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager

internal class ReplaceFolderColorsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.selectedDirectory() != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val directory = event.selectedDirectory() ?: return

        object : Task.Backgroundable(project, FOLDER_COLOR_REPLACEMENT_TITLE, true) {
            private lateinit var replacement: FolderColorReplacement

            override fun run(indicator: ProgressIndicator) {
                replacement = FolderColorReplacement.scan(project, directory, indicator)
            }

            override fun onSuccess() {
                if (!project.isDisposed) {
                    val message = replacement.apply(project)

                    WindowManager.getInstance().getStatusBar(project)?.setInfo(message)
                }
            }
        }.queue()
    }
}

internal class TaigaProjectViewActions : DefaultActionGroup() {
    init {
        templatePresentation.isHideGroupIfEmpty = true
    }
}

private fun AnActionEvent.selectedDirectory(): VirtualFile? {
    val project = project ?: return null

    val selection = getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
    val file = if (selection == null) getData(CommonDataKeys.VIRTUAL_FILE) else selection.singleOrNull()

    return file?.takeIf { it.isColorReplacementDirectory(project) }
}

private fun VirtualFile.isColorReplacementDirectory(project: Project): Boolean =
    isValid &&
        isDirectory &&
        isInLocalFileSystem &&
        ProjectFileIndex.getInstance(project).isInContent(this) &&
        generateSequence(this) { it.parent }.none { it.name == "node_modules" || it.`is`(VFileProperty.SYMLINK) }
