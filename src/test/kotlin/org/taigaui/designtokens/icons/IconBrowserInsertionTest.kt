package org.taigaui.designtokens.icons

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class IconBrowserInsertionTest : BasePlatformTestCase() {
    fun testInsertsIconNameAtCaretWithoutChangingSurroundingText() {
        myFixture.configureByText("example.html", """<button iconStart="<caret>"></button>""")
        val editor = myFixture.editor
        val name = "@tui.actions.search"

        WriteCommandAction.runWriteCommandAction(project) {
            insertIconSnippet(editor, name)
        }

        assertEquals("""<button iconStart="@tui.actions.search"></button>""", editor.document.text)
        assertEquals(editor.document.text.indexOf(name) + name.length, editor.caretModel.offset)
    }

    fun testReplacesOnlySelectedTextAndClearsSelection() {
        myFixture.configureByText("example.html", """<div>temporary text</div>""")
        val editor = myFixture.editor
        val start = editor.document.text.indexOf("temporary")
        val end = start + "temporary".length
        val snippet = """<tui-icon icon="@tui.actions.search" />"""

        editor.selectionModel.setSelection(start, end)

        WriteCommandAction.runWriteCommandAction(project) {
            insertIconSnippet(editor, snippet)
        }

        assertEquals("<div>$snippet text</div>", editor.document.text)
        assertFalse(editor.selectionModel.hasSelection())
        assertEquals(start + snippet.length, editor.caretModel.offset)
    }
}
