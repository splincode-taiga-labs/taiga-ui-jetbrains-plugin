package org.taigaui.designtokens.documentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class TaigaDocumentationExamplePrettierTest {
    private val sourceFile = Path.of("/project/src/component.html")
    private val icon =
        """<tui-icon [background]="backgroundIcon" [icon]="icon" """ +
            """[style.background]="backgroundColor" [style.color]="color" [style.font-size.px]="size" />"""

    @Test
    fun `uses project Prettier when it is available and preserves bindings`() {
        val expected =
            """
            <tui-icon
              [background]="backgroundIcon"
              [icon]="icon"
              [style.background]="backgroundColor"
              [style.color]="color"
              [style.font-size.px]="size"
            />
            """.trimIndent()
        var calls = 0
        val prettier =
            TaigaDocumentationExamplePrettier(
                commandFor = { listOf("node", "prettier.cjs") },
                run = { _, code ->
                    assertEquals(icon, code)
                    calls++
                    expected + "\n"
                },
            )

        assertEquals(expected, prettier.format(icon, sourceFile))
        assertEquals(expected, prettier.format(icon, sourceFile))
        assertEquals(1, calls)
        assertEquals(icon, icon)
    }

    @Test
    fun `uses custom formatter without project Prettier`() {
        val formatter = TaigaDocumentationExamplePrettier(commandFor = { null })
        val result = formatter.format(icon, sourceFile)

        assertEquals(formattedDocumentationExample(icon), result)
        assertTrue(result.startsWith("<tui-icon\n    [background]"))
        assertTrue(result.contains("\n    [style.font-size.px]=\"size\"\n/>"))
        assertTrue(documentationExamplePreservesContent(icon, result))
    }

    @Test
    fun `falls back if Prettier fails or modifies Angular expressions`() {
        val unavailable =
            TaigaDocumentationExamplePrettier(
                commandFor = { listOf("node") },
                run = { _, _ -> null },
            )
        val unsafe =
            TaigaDocumentationExamplePrettier(
                commandFor = { listOf("node") },
                run = { _, _ -> icon.replace("backgroundIcon", "differentValue") },
            )

        assertEquals(formattedDocumentationExample(icon), unavailable.format(icon, sourceFile))
        assertEquals(formattedDocumentationExample(icon), unsafe.format(icon, sourceFile))
    }

    @Test
    fun `custom formatter preserves Angular markup and quoted attribute whitespace`() {
        val source = """<button tuiButton [title]="'a > b'" (click)="save('a b')">Save</button>"""
        val result = formattedDocumentationExample(source)

        assertTrue(documentationExamplePreservesContent(source, result))
        assertTrue(result.contains("[title]=\"'a > b'\""))
        assertTrue(result.contains("(click)=\"save('a b')\""))
        assertTrue(result.endsWith(">Save</button>"))
        assertEquals("@if (ready) { <span>Ready</span> }", formattedDocumentationExample("@if (ready) { <span>Ready</span> }"))
        assertEquals("<button [size]=\"size\"", formattedDocumentationExample("<button [size]=\"size\""))
    }

    @Test
    fun `normalizes only stray parentheses on simple types`() {
        assertEquals("string", cleanDocumentationType("string)"))
        assertEquals(listOf("string"), documentationTypeTokens("string)"))
        assertEquals("string", cleanDocumentationType("string"))
        assertEquals("() => string)", cleanDocumentationType("() => string)"))
        assertFalse(documentationTypeTokens("() => string)").contains("string"))
    }
}
