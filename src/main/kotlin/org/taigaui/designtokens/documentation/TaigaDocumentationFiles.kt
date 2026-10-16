package org.taigaui.designtokens.documentation

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/** Also supports project content mounted in a non-local virtual filesystem. */
internal fun findDocumentationFile(project: Project, path: Path): VirtualFile? =
    LocalFileSystem.getInstance().findFileByNioFile(path)
        ?: ProjectRootManager.getInstance(project).contentRoots.firstNotNullOfOrNull { root ->
            val value = path.toString().replace('\\', '/')
            if (!value.startsWith(root.path + "/")) null
            else root.findFileByRelativePath(value.removePrefix(root.path + "/"))
        }
