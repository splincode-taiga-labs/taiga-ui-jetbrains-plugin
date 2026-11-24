package org.taigaui.designtokens.documentation

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

class TaigaShowDocumentationCardActionTest : TaigaQuickDocumentationControllerTestCase() {
    fun testActionIsDisabledForUnsupportedFilesAndMissingEditor() {
        myFixture.configureByText("styles.css", ".button { color: red; }")
        val action = TaigaShowDocumentationCardAction()
        val event = event(action, project, myFixture.editor, myFixture.file)

        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
        action.update(event)
        assertFalse(event.presentation.isEnabled)

        myFixture.configureByText("component.ts", "export class Example {}")
        val typescript = event(action, project, myFixture.editor, myFixture.file)
        action.update(typescript)
        assertTrue(typescript.presentation.isEnabled)

        val missingEditor = event(action, project, null, myFixture.file)
        action.update(missingEditor)
        assertFalse(missingEditor.presentation.isEnabled)
        action.actionPerformed(missingEditor)
        assertNull(panelOrNull())

        val missingProject = event(action, null, myFixture.editor, myFixture.file)
        action.update(missingProject)
        assertFalse(missingProject.presentation.isEnabled)
        action.actionPerformed(missingProject)
        assertNull(panelOrNull())
    }

    fun testActionOpensDocumentationForTheCaretTarget() {
        configure("<button tuiButton size=\"m\">Save</button>", "size")
        val action = TaigaShowDocumentationCardAction()
        val event = event(action, project, myFixture.editor, myFixture.file)

        action.update(event)
        assertTrue(event.presentation.isEnabled)
        action.actionPerformed(event)

        await("documentation action") { panelOrNull() != null }
        val resolved = view().resolved as TaigaResolvedDocumentation.Member
        assertEquals("size", resolved.property.name)
        assertFalse(panel().compactHover)
    }

    private fun event(
        action: TaigaShowDocumentationCardAction,
        project: Project?,
        editor: Editor?,
        file: PsiFile?,
    ): AnActionEvent {
        val context =
            DataContext { id ->
                when (id) {
                    CommonDataKeys.PROJECT.name -> project
                    CommonDataKeys.EDITOR.name -> editor
                    CommonDataKeys.PSI_FILE.name -> file
                    else -> null
                }
            }
        return AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, context)
    }
}
