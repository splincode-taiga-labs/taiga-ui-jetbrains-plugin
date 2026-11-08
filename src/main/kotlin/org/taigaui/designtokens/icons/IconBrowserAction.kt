package org.taigaui.designtokens.icons

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import java.nio.file.Path

class IconBrowserAction : AnAction() {
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val selectedFile = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val editorFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull { it.isInLocalFileSystem }
        val selectedPath = selectedFile?.takeIf { it.isInLocalFileSystem }?.path
        val sourcePath = selectedPath ?: editorFile?.path ?: project.basePath ?: return

        IconBrowserDialog(project, Path.of(sourcePath)).show()
    }
}
