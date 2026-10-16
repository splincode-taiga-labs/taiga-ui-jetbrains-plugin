package org.taigaui.designtokens.documentation

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class TaigaQuickDocumentationActionContextTest : BasePlatformTestCase() {
    fun testUnrelatedUncommittedAndCommittedEditsKeepTheCardCurrent() {
        val unrelated = myFixture.tempDirFixture.createFile("unrelated.ts", "export const value = 1;")
        myFixture.configureByText("component.html", "<button tuiButton></button>")
        val context = TaigaDocumentationActionContext(project, myFixture.editor.document)
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(unrelated))
        WriteCommandAction.runWriteCommandAction(project) { document.setText("export const value = 2;") }
        assertTrue(context.isCurrent())
        PsiDocumentManager.getInstance(project).commitDocument(document)
        assertTrue(context.isCurrent())
    }

    fun testAnImportedLocalFileInvalidatesBeforeAndAfterCommit() {
        val dependency = myFixture.tempDirFixture.createFile("api.ts", "export const value = 1;")
        val source = myFixture.tempDirFixture.createFile("component.ts", "import {value} from './api';")
        myFixture.configureFromExistingVirtualFile(source)
        myFixture.doHighlighting()
        val context = TaigaDocumentationActionContext(project, myFixture.editor.document)
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(dependency))
        WriteCommandAction.runWriteCommandAction(project) { document.setText("export const value = 2;") }
        assertFalse(context.isCurrent())
        PsiDocumentManager.getInstance(project).commitDocument(document)
        assertFalse(context.isCurrent())
    }

    fun testInstalledDeclarationsInvalidateAnExplicitApiDependency() {
        val declaration =
            myFixture.tempDirFixture.createFile(
                "node_modules/@taiga-ui/core/button.d.ts",
                "export declare class TuiButton {}",
            )
        myFixture.configureByText("component.html", "<button tuiButton></button>")
        val context =
            TaigaDocumentationActionContext(
                project,
                myFixture.editor.document,
                listOf(Path.of(declaration.path)),
            )
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(declaration))
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "// API changed\n") }
        assertFalse(context.isCurrent())
    }

    fun testAnEditedHostAlwaysInvalidatesTheCard() {
        myFixture.configureByText("component.html", "<button tuiButton></button>")
        val context = TaigaDocumentationActionContext(project, myFixture.editor.document)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "\n") }
        assertFalse(context.isCurrent())
    }
}
