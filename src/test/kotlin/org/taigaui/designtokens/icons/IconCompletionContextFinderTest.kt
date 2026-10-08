package org.taigaui.designtokens.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconCompletionContextFinderTest {
    @Test
    fun `finds icon prefix inside single quoted string`() {
        assertEquals(
            IconCompletionContext("@tui.fancy.medium.inf"),
            context("const icon = '@tui.fancy.medium.inf|';"),
        )
    }

    @Test
    fun `finds proprietary icon prefix inside status object`() {
        assertEquals(
            IconCompletionContext("@tui.fancy.medium.info"),
            context(
                """
                const icons = {
                    info: '@tui.fancy.medium.info|',
                    warning: '@tui.fancy.medium.alert',
                    neutral: '@tui.fancy.medium.info-circle',
                    error: '@tui.fancy.medium.alert',
                    success: '@tui.fancy.medium.check-circle',
                };
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `finds icon prefix inside angular html attribute`() {
        assertEquals(
            IconCompletionContext("@tui.flags.a"),
            context("<button iconStart='@tui.flags.a|'></button>"),
        )
    }

    @Test
    fun `finds icon prefix inside arbitrary html attribute`() {
        assertEquals(
            IconCompletionContext("@tui.a-arrow"),
            context("<div data-icon='@tui.a-arrow|'></div>"),
        )
    }

    @Test
    fun `finds partial tui prefix inside angular html attribute`() {
        assertEquals("@", partialPrefix("<button iconStart=\"@|\"></button>"))
        assertEquals("@t", partialPrefix("<button iconStart=\"@t|\"></button>"))
        assertEquals("@tu", partialPrefix("<button iconStart=\"@tu|\"></button>"))
        assertEquals("@tui", partialPrefix("<button iconStart=\"@tui|\"></button>"))
    }

    @Test
    fun `ignores partial tui prefix outside a string`() {
        assertNull(partialPrefix("const icon = @tui|;"))
    }

    @Test
    fun `finds initial tui prefix before typed dot`() {
        assertEquals(
            IconCompletionContext("@tui."),
            contextAfterTyping("<button iconStart=\"@tui|\"></button>", '.'),
        )
    }

    @Test
    fun `finds continued static html prefix before typed character`() {
        assertEquals(
            IconCompletionContext("@tui.pr"),
            contextAfterTyping("<button iconStart=\"@tui.p|\"></button>", 'r'),
        )
    }

    @Test
    fun `rejects invalid offsets and non icon characters`() {
        val text = "'@tui.flags.a'"

        assertNull(IconCompletionContextFinder.find(text, -1))
        assertNull(IconCompletionContextFinder.find(text, text.length + 1))
        assertNull(IconCompletionContextFinder.findPartialPrefix(text, -1))
        assertNull(IconCompletionContextFinder.findPartialPrefix(text, text.length + 1))
        assertNull(IconCompletionContextFinder.findAfterTyping(text, 6, '!'))
    }

    @Test
    fun `icon completion context data class exposes stable value semantics`() {
        val context = IconCompletionContext("@tui.flags.a")
        val copy = context.copy()

        assertEquals("@tui.flags.a", context.component1())
        assertEquals(context, copy)
        assertEquals(context.hashCode(), copy.hashCode())
        assertTrue(context.toString().contains("@tui.flags.a"))
    }

    @Test
    fun `ignores tui prefix outside a string`() {
        assertNull(context("const icon = @tui.flags.a|;"))
    }

    @Test
    fun `ignores unrelated string`() {
        assertNull(context("const icon = 'tui.flags.a|';"))
    }

    private fun context(value: String): IconCompletionContext? {
        val offset = value.indexOf('|')
        val text = value.replace("|", "")

        return IconCompletionContextFinder.find(text, offset)
    }

    private fun partialPrefix(value: String): String? {
        val offset = value.indexOf('|')
        val text = value.replace("|", "")

        return IconCompletionContextFinder.findPartialPrefix(text, offset)
    }

    private fun contextAfterTyping(
        value: String,
        charTyped: Char,
    ): IconCompletionContext? {
        val offset = value.indexOf('|')
        val text = value.replace("|", "")

        return IconCompletionContextFinder.findAfterTyping(text, offset, charTyped)
    }
}
