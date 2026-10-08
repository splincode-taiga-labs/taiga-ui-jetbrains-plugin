package org.taigaui.designtokens.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostEventPluginCompletionContextCoverageTest {
    @Test
    fun `parses plain events and valid global targets`() {
        assertContext(
            HostEventPluginCompletionContext.parse("(click"),
            HostEventPluginCompletionContext.Kind.EVENT,
            "click",
        )
        assertContext(
            HostEventPluginCompletionContext.parse("(visualViewport>resize"),
            HostEventPluginCompletionContext.Kind.EVENT,
            "resize",
        )
        assertContext(
            HostEventPluginCompletionContext.parse("(window_name>custom-event"),
            HostEventPluginCompletionContext.Kind.EVENT,
            "custom-event",
        )

        assertNull(HostEventPluginCompletionContext.parse("click"))
        assertNull(HostEventPluginCompletionContext.parse("(window!>resize"))
        assertNull(HostEventPluginCompletionContext.parse("(window>>resize"))
        assertNull(HostEventPluginCompletionContext.parse("(window>bad!event"))
    }

    @Test
    fun `parses modifier chains and normalizes modifier identities`() {
        val simple = requireNotNull(HostEventPluginCompletionContext.parse("(click.stop.ca"))

        assertEquals(HostEventPluginCompletionContext.Kind.MODIFIER, simple.kind)
        assertEquals("ca", simple.prefix)
        assertEquals(setOf("stop"), simple.usedModifierIdentities)

        val aliases =
            requireNotNull(
                HostEventPluginCompletionContext.parse(
                    "(click.silent.debounce~100ms.throttle~2s.",
                ),
            )

        assertEquals(
            setOf("zoneless", "debounce", "throttle"),
            aliases.usedModifierIdentities,
        )

        assertNull(HostEventPluginCompletionContext.parse("(click.stop.stop."))
        assertNull(HostEventPluginCompletionContext.parse("(click.unknown.stop."))
    }

    @Test
    fun `accepts complete key events and rejects incomplete key chains`() {
        assertContext(
            HostEventPluginCompletionContext.parse("(keydown.enter.stop."),
            HostEventPluginCompletionContext.Kind.MODIFIER,
            "",
        )
        assertContext(
            HostEventPluginCompletionContext.parse("(keyup.control.f12.once.o"),
            HostEventPluginCompletionContext.Kind.MODIFIER,
            "o",
        )

        assertNull(HostEventPluginCompletionContext.parse("(keydown.nope.stop."))
        assertNull(HostEventPluginCompletionContext.parse("(keydown.shift.stop."))
    }

    @Test
    fun `finds context before caret across binding boundaries`() {
        val text = "prefix: '(visualViewport>res"

        assertContext(
            HostEventPluginCompletionContext.findBeforeCaret(text, text.length),
            HostEventPluginCompletionContext.Kind.EVENT,
            "res",
        )
        assertNull(
            HostEventPluginCompletionContext.findBeforeCaret(
                "plain text",
                "plain text".length,
            ),
        )
        assertNull(
            HostEventPluginCompletionContext.findBeforeCaret(
                "'(click",
                1,
            ),
        )
    }

    @Test
    fun `finds context after appending typed character and clamps caret offsets`() {
        assertContext(
            HostEventPluginCompletionContext.findAfterTyping(
                text = "(click",
                caretOffset = Int.MAX_VALUE,
                charTyped = '.',
            ),
            HostEventPluginCompletionContext.Kind.MODIFIER,
            "",
        )
        assertNull(
            HostEventPluginCompletionContext.findAfterTyping(
                text = "(",
                caretOffset = -1,
                charTyped = 'r',
            ),
        )
        assertContext(
            HostEventPluginCompletionContext.findAfterTyping(
                text = "(",
                caretOffset = 1,
                charTyped = 'r',
            ),
            HostEventPluginCompletionContext.Kind.EVENT,
            "r",
        )

        val longBinding = "(" + "x".repeat(200)

        assertNull(
            HostEventPluginCompletionContext.findBeforeCaret(
                longBinding,
                longBinding.length,
            ),
        )
    }

    private fun assertContext(
        context: HostEventPluginCompletionContext?,
        kind: HostEventPluginCompletionContext.Kind,
        prefix: String,
    ) {
        val value = requireNotNull(context)

        assertEquals(kind, value.kind)
        assertEquals(prefix, value.prefix)
        assertTrue(value.usedModifierIdentities.all(String::isNotBlank))
    }
}
