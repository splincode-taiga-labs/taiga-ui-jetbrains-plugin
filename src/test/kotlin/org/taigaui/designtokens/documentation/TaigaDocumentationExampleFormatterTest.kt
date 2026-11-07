package org.taigaui.designtokens.documentation

import com.intellij.openapi.command.WriteCommandAction

/** Runs against the bundled WebStorm HTML/Angular formatter, not a mocked Prettier process. */
class TaigaDocumentationExampleFormatterTest : TaigaDocumentationPopupTestCase() {
    fun testNativeFormatterUsesOnlyAnInMemoryCopy() {
        val source =
            """<button tuiButton type="button" [disabled]="count > 2" """ +
                """[size]="size" (click)="save(item)">Save</button>"""
        myFixture.configureByText("component.html", source)
        val originalDocument = myFixture.editor.document.text
        val originalPsi = myFixture.file.text

        val formatted = format(source)

        assertEquals(originalDocument, myFixture.editor.document.text)
        assertEquals(originalPsi, myFixture.file.text)
        assertTrue(documentationExamplePreservesContent(source, formatted))
        assertTrue(formatted.contains("[disabled]=\"count > 2\""))
        assertTrue(formatted.contains("(click)=\"save(item)\""))
        assertTrue(formatted.contains(">Save</button>"))
    }

    fun testNativeFormatterPreservesAngularTwoWayBindingsAndLiteralValues() {
        val source =
            """<input tuiTextfield [(ngModel)]="value" [attr.aria-label]="label" """ +
                """[disabled]="value === 'a b'" placeholder="hello world" />"""

        val formatted = format(source)

        assertTrue(documentationExamplePreservesContent(source, formatted))
        assertTrue(formatted.contains("[(ngModel)]=\"value\""))
        assertTrue(formatted.contains("[disabled]=\"value === 'a b'\""))
        assertTrue(formatted.contains("placeholder=\"hello world\""))
    }

    fun testNativeFormatterPreservesModernAngularControlFlow() {
        val samples =
            listOf(
                "@if (visible) { <button tuiButton (click)=\"save()\">Save</button> } @else { <p>Hidden</p> }",
                "@for (item of items; track item.id) { <span [title]=\"item.name\">{{ item.name }}</span> }",
                "@switch (status) { @case ('done') { <b>Done</b> } @default { <i>Pending</i> } }",
                "@let message = user.name; <span>{{ message }}</span>",
                "@defer (on viewport) { <tui-icon icon=\"@tui.check\" /> } @placeholder { <span>Wait</span> }",
            )

        samples.forEach { source ->
            val formatted = format(source)
            assertTrue("Angular template changed: " + source, documentationExamplePreservesContent(source, formatted))
        }
    }

    fun testNativeFormatterDoesNotReinterpretHtmlTextAndWhitespace() {
        val samples =
            listOf(
                "<pre>  Important   spaces\n  next line</pre>",
                "<textarea>two  spaces\nnew line</textarea>",
                "<span>before </span><span> after</span>",
                "<div><!-- leave comment unchanged --><span>Value</span></div>",
                "<button [title]=\"a > b && c < d\">Value</button>",
            )

        samples.forEach { source ->
            assertTrue(documentationExamplePreservesContent(source, format(source)))
        }
    }

    fun testNativeFormatterFallsBackForIncompleteMarkup() {
        val source = "<button tuiButton [disabled]=\"count > 2\""
        assertEquals(source, format(source))
    }

    fun testGuardAllowsOnlyWhitespaceChangesWithinTags() {
        val original = "<button tuiButton [disabled]=\"count > 2\">Save</button>"
        val reformatted = "<button\n    tuiButton\n    [disabled]=\"count > 2\"\n>Save</button>"

        assertTrue(documentationExamplePreservesContent(original, reformatted))
        assertFalse(documentationExamplePreservesContent(original, reformatted.replace("count > 2", "count > 3")))
        assertFalse(documentationExamplePreservesContent(original, reformatted.replace("Save", "Saved")))
        assertFalse(
            documentationExamplePreservesContent(
                "<input placeholder=\"hello world\" />",
                "<input placeholder=\"hello  world\" />",
            ),
        )
    }

    fun testGuardRejectsModifiedAngularBlocksAndHtmlComments() {
        assertFalse(
            documentationExamplePreservesContent(
                "@if (ready) { <span>Ready</span> }",
                "@if (ready) {\n  <span>Ready</span>\n}",
            ),
        )
        assertFalse(
            documentationExamplePreservesContent(
                "<div><!-- some comment --></div>",
                "<div><!-- some  comment --></div>",
            ),
        )
    }

    fun testLargeExamplesAreReturnedWithoutChanges() {
        val source = "<span>" + "A".repeat(4_500) + "</span>"
        assertEquals(source, format(source))
    }

    private fun format(source: String): String {
        var formatted = ""
        WriteCommandAction.runWriteCommandAction(project) {
            formatted = TaigaDocumentationExampleFormatter(project).formatInWriteAction(source)
        }
        return formatted
    }
}
