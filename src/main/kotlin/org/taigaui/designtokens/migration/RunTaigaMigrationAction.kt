package org.taigaui.designtokens.migration

import com.intellij.ide.trustedProjects.TrustedProjects.isProjectTrusted
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import org.taigaui.designtokens.project.selectedProjectDirectory
import java.nio.file.Path

internal class RunTaigaMigrationAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible =
            event.project?.let(::isProjectTrusted) == true &&
            event.selectedProjectDirectory() != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project?.takeIf(::isProjectTrusted) ?: return
        val directory = event.selectedProjectDirectory() ?: return

        project.basePath?.let { root ->
            project.service<TaigaMigrationRunner>().run(
                directory.toNioPath(),
                Path.of(root),
            )
        }
    }
}
