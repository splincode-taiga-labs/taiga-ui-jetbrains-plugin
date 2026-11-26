package org.taigaui.designtokens.migration

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
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
            val invalid =
                listOf(event(action, null, root), event(action, project, null), event(action, project, source))
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

        val content = """{"migrations":[{"package":"@taiga-ui/cdk","name":"updateToV5","version":"5.0.0"}]}"""
        Files.writeString(plan, content)
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
    }

    fun testMigrationSavesWorkspaceDocumentsWithoutTouchingOtherFolders() {
        val root = getOrCreateProjectBaseDir()
        val path = root.toNioPath()
        val workspace = Files.createDirectories(path.resolve("app"))
        Files.writeString(workspace.resolve("source.ts"), "original")
        Files.writeString(path.resolve("outside.ts"), "original")
        root.refresh(false, true)
        val manager = FileDocumentManager.getInstance()
        val inside = requireNotNull(root.findFileByRelativePath("app/source.ts"))
        val outside = requireNotNull(root.findChild("outside.ts"))
        val insideDocument = requireNotNull(manager.getDocument(inside))
        val outsideDocument = requireNotNull(manager.getDocument(outside))

        WriteCommandAction.runWriteCommandAction(project) {
            insideDocument.setText("changed")
            outsideDocument.setText("changed")
        }

        try {
            saveWorkspaceDocuments(workspace)
            assertFalse(manager.isDocumentUnsaved(insideDocument))
            assertTrue(manager.isDocumentUnsaved(outsideDocument))
            assertEquals("changed", Files.readString(workspace.resolve("source.ts")))
            assertEquals("original", Files.readString(path.resolve("outside.ts")))
        } finally {
            manager.saveDocument(outsideDocument)
        }
    }

    fun testMigrationRejectsUnsavedMigrationPlan() {
        val root = getOrCreateProjectBaseDir()
        val path = root.toNioPath()
        Files.writeString(path.resolve("migrations.json"), "{}")
        root.refresh(false, true)
        val manager = FileDocumentManager.getInstance()
        val file = requireNotNull(root.findChild("migrations.json"))
        val document = requireNotNull(manager.getDocument(file))

        WriteCommandAction.runWriteCommandAction(project) { document.setText("""{"migrations":[]}""") }

        try {
            val failure =
                org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                    saveWorkspaceDocuments(path)
                }
            assertTrue(requireNotNull(failure.message).contains("Save migrations.json"))
            assertTrue(manager.isDocumentUnsaved(document))
            assertEquals("{}", Files.readString(path.resolve("migrations.json")))
        } finally {
            manager.saveDocument(document)
        }
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
