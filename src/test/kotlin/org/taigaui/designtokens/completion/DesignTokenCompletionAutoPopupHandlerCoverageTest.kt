package org.taigaui.designtokens.completion

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DesignTokenCompletionAutoPopupHandlerCoverageTest : BasePlatformTestCase() {
    fun testSchedulesAutoPopupForTaigaTokenCharacter() {
        val file =
            myFixture.configureByText(
                "styles.css",
                ".demo { color: var(--tui-text); }",
            )
        val token = "--tui-text"
        val caretOffset = myFixture.editor.document.text.indexOf(token) + token.length

        myFixture.editor.caretModel.moveToOffset(caretOffset)

        assertEquals(
            TypedHandlerDelegate.Result.CONTINUE,
            DesignTokenCompletionAutoPopupHandler().checkAutoPopup(
                't',
                project,
                myFixture.editor,
                file,
            ),
        )
    }

    fun testTokenNameCharacterClassification() {
        assertTrue('a'.isTokenNameCharacter())
        assertTrue('1'.isTokenNameCharacter())
        assertTrue('-'.isTokenNameCharacter())
        assertTrue('_'.isTokenNameCharacter())
        assertFalse('!'.isTokenNameCharacter())
    }
}
