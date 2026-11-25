package org.taigaui.designtokens.migration

import com.intellij.ide.trustedProjects.TrustedProjects.isProjectTrusted
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import org.taigaui.designtokens.project.selectedProjectDirectory
import java.io.IOException
import java.nio.file.Path

internal class RunTaigaMigrationAction : TaigaMigrationAction(existingPlan = false)

internal class RunExistingNxMigrationAction : TaigaMigrationAction(existingPlan = true)

internal abstract class TaigaMigrationAction(
    private val existingPlan: Boolean,
) : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project?.takeIf(::isProjectTrusted)
        val directory = project?.let { event.selectedProjectDirectory() }
        val root = project?.basePath

        event.presentation.isEnabledAndVisible =
            directory != null &&
            root != null &&
            (!existingPlan || hasExistingNxPlan(directory.toNioPath(), Path.of(root)))
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project?.takeIf(::isProjectTrusted) ?: return
        val directory = event.selectedProjectDirectory() ?: return

        project.basePath?.let { root ->
            project.service<TaigaMigrationRunner>().run(
                directory.toNioPath(),
                Path.of(root),
                existingPlan,
            )
        }
    }
}

private fun hasExistingNxPlan(
    selected: Path,
    root: Path,
): Boolean =
    try {
        prepareExistingNxMigration(selected, root)
        true
    } catch (_: IOException) {
        false
    } catch (_: IllegalStateException) {
        false
    }
