package org.taigaui.designtokens.project

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile

internal fun AnActionEvent.selectedProjectDirectory(): VirtualFile? {
    val project = project ?: return null
    val selection = getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
    val file = if (selection == null) getData(CommonDataKeys.VIRTUAL_FILE) else selection.singleOrNull()

    return file?.takeIf {
        it.isValid &&
            it.isDirectory &&
            it.isInLocalFileSystem &&
            ProjectFileIndex.getInstance(project).isInContent(it) &&
            generateSequence(it) { directory -> directory.parent }
                .none { directory -> directory.name == "node_modules" || directory.`is`(VFileProperty.SYMLINK) }
    }
}
