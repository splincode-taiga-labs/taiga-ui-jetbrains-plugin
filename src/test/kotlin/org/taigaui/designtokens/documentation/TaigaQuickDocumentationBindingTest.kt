package org.taigaui.designtokens.documentation

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TaigaQuickDocumentationBindingTest : BasePlatformTestCase() {
    fun testBoundLiteralReplacementPreservesAttributeAndHasSingleUndo() {
        val binding = binding("""<button tuiButton [size] = "'s'">Save</button>""")
        val editor = TaigaDocumentationBindingEditor(project, myFixture.editor.document, binding, listOf("s", "m", "l"))
        try {
            assertTrue(editor.apply("m").startsWith("Applied"))
            assertEquals("""<button tuiButton [size] = "'m'">Save</button>""", myFixture.editor.document.text)
            val textEditor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
            UndoManager.getInstance(project).undo(textEditor)
            assertEquals("""<button tuiButton [size] = "'s'">Save</button>""", myFixture.editor.document.text)
        } finally {
            editor.dispose()
        }
    }

    fun testRangeTracksUnrelatedEditsAndAllowsRepeatedChoices() {
        val binding = binding("""<button tuiButton size="s">Save</button>""")
        val editor = TaigaDocumentationBindingEditor(project, myFixture.editor.document, binding, listOf("s", "m", "l"))
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                myFixture.editor.document.insertString(0, "<!-- note -->")
            }
            assertTrue(editor.apply("m").startsWith("Applied"))
            assertTrue(editor.apply("l").startsWith("Applied"))
            assertEquals("""<!-- note --><button tuiButton size="l">Save</button>""", myFixture.editor.document.text)
        } finally {
            editor.dispose()
        }
    }

    fun testChangedBindingIsNeverOverwritten() {
        val binding = binding("""<button tuiButton size="s">Save</button>""")
        val editor = TaigaDocumentationBindingEditor(project, myFixture.editor.document, binding, listOf("s", "m"))
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                myFixture
                    .editor
                    .document
                    .replaceString(binding.startOffset, binding.endOffset, "size=\"custom\"")
            }
            assertTrue(editor.apply("m").startsWith("Binding changed"))
            assertTrue(
                myFixture.editor.document.text
                    .contains("custom"),
            )
        } finally {
            editor.dispose()
        }
    }

    fun testChangedOwnerCannotReuseThePinnedInputType() {
        val binding = binding("""<button tuiButton size="s">Save</button>""")
        val document = myFixture.editor.document
        val editor = TaigaDocumentationBindingEditor(project, document, binding, listOf("s", "m"))
        try {
            val start = document.text.indexOf("tuiButton")
            WriteCommandAction.runWriteCommandAction(project) {
                document.replaceString(start, start + "tuiButton".length, "tuiOther")
            }
            assertFalse(editor.apply("m").startsWith("Applied"))
            assertTrue(document.text.contains("size=\"s\""))
        } finally {
            editor.dispose()
        }
    }

    fun testDynamicExpressionsAndUnknownValuesCannotBeApplied() {
        val binding = binding("""<button tuiButton [size]="compact ? 's' : 'm'">Save</button>""")
        assertNull(binding.literal)
        val before = myFixture.editor.document.text
        val editor = TaigaDocumentationBindingEditor(project, myFixture.editor.document, binding, listOf("s", "m"))
        try {
            assertEquals("Copy this value instead", editor.apply("s"))
            assertEquals(before, myFixture.editor.document.text)
        } finally {
            editor.dispose()
        }
    }

    fun testReadOnlyDocumentAndOutOfTypeValuesDoNotChangeSource() {
        val binding = binding("""<button tuiButton size="s">Save</button>""")
        val document = myFixture.editor.document
        val before = document.text
        val editor = TaigaDocumentationBindingEditor(project, document, binding, listOf("s", "m"))
        try {
            assertEquals("Copy this value instead", editor.apply("unknown"))
            document.setReadOnly(true)
            assertTrue(editor.apply("m").startsWith("File is read-only"))
            assertEquals(before, document.text)
        } finally {
            document.setReadOnly(false)
            editor.dispose()
        }
    }

    fun testSingleQuotedHtmlAttributeRemainsValid() {
        val binding = binding("""<button tuiButton [size]='"s"'>Save</button>""")
        assertEquals("s", binding.literal)
        assertEquals("[size]='\"m\"'", binding.replacement("m"))
    }

    fun testOnlyCompleteFiniteStringUnionsAuthorizeValues() {
        assertEquals(listOf("s", "m"), finiteStringValues("'s' | \"m\""))
        listOf("string | 's'", "{ size: 's' | 'm' }", "('s' | 'm')[]", "'s\" | 'm'", "TuiSize").forEach {
            assertTrue(it, finiteStringValues(it).isEmpty())
        }
    }

    fun testRequiredInputsAreReportedOnlyWhenExplicit() {
        val local =
            TaigaLocalDocumentationParser
                .parse(
                    "class TuiExample { size = input.required<string>(); label = input(''); }",
                )
        assertEquals(setOf("size"), local.requiredInputs)
    }

    private fun binding(text: String): TaigaDocumentationBinding {
        val file = myFixture.configureByText("template.html", text)
        return PsiTreeUtil
            .findChildrenOfType(file, XmlAttribute::class.java)
            .first { it.bindingName().contains("size") }
            .documentationBinding()!!
    }
}
