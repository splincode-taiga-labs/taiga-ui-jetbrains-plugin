package org.taigaui.designtokens.migration

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import java.nio.file.Files

class TaigaMigrationActionTest : HeavyPlatformTestCase() {
    override fun isCreateDirectoryBasedProject(): Boolean = true

    fun testActionsRequireAProjectAndDirectorySelection() {
        val root = getOrCreateProjectBaseDir()
        PsiTestUtil.addContentRoot(module, root)
        val file = root.toNioPath().resolve("source.ts")
        Files.writeString(file, "")
        root.refresh(false, true)
        val source = requireNotNull(root.findChild("source.ts"))

        for (action in listOf(RunTaigaMigrationAction(), RunExistingNxMigrationAction())) {
            assertEquals(ActionUpdateThread.BGT, action.getActionUpdateThread())
            val invalid = listOf(event(action, null, root), event(action, project, null), event(action, project, source))
            for (event in invalid) {
                action.update(event)
                assertFalse(event.presentation.isEnabledAndVisible)
                action.actionPerformed(event)
            }
        }

        val action = RunTaigaMigrationAction()
        val selected = event(action, project, root)
        action.update(selected)
        assertTrue(selected.presentation.isEnabledAndVisible)
    }

    fun testExistingPlanActionRequiresAValidNxMigration() {
        val root = getOrCreateProjectBaseDir()
        PsiTestUtil.addContentRoot(module, root)
        val path = root.toNioPath()
        val nx = Files.createDirectories(path.resolve("node_modules/nx/bin"))
        Files.writeString(nx.resolve("nx.js"), "")
        Files.writeString(path.resolve("nx.json"), "{}")
        Files.writeString(path.resolve("package.json"), "{}")
        root.refresh(false, true)

        val action = RunExistingNxMigrationAction()
        val event = event(action, project, root)
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible)

        val plan = path.resolve("migrations.json")
        Files.writeString(plan, "{}")
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible)

        Files.writeString(plan, """{"migrations":[{"package":"@taiga-ui/cdk","name":"updateToV5","version":"5.0.0"}]}""")
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
    }

    private fun event(
        action: TaigaMigrationAction,
        project: Project?,
        file: VirtualFile?,
    ): AnActionEvent {
        val context =
            DataContext { id ->
                when (id) {
                    CommonDataKeys.PROJECT.name -> project
                    CommonDataKeys.VIRTUAL_FILE.name -> file
                    else -> null
                }
            }

        return AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, context)
    }
}
