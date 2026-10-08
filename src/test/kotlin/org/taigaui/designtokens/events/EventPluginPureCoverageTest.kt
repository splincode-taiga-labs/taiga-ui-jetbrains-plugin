package org.taigaui.designtokens.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventPluginPureCoverageTest {
    @Test
    fun `finds event plugin binding inside attribute and accepts whitespace before value`() {
        val text = """<button (click.stop.prevent)   = "submit()"></button>"""
        val offset = text.indexOf("stop") + 2
        val reference =
            requireNotNull(
                EventPluginBindingAtOffsetFinder.find(
                    file = null,
                    text = text,
                    offset = offset,
                ),
            )

        assertEquals("(click.stop.prevent)", reference.binding.source)
        assertEquals(text.indexOf("(click"), reference.startOffset)
        assertEquals(text.indexOf(")") + 1, reference.endOffset)
    }

    @Test
    fun `binding finder rejects invalid offsets boundaries and attributes without values`() {
        assertNull(EventPluginBindingAtOffsetFinder.find("", 0))
        assertNull(EventPluginBindingAtOffsetFinder.find("(click.stop)=\"x\"", -1))
        assertNull(EventPluginBindingAtOffsetFinder.find("(click.stop)=\"x\"", 100))
        assertNull(EventPluginBindingAtOffsetFinder.find("(click.stop)", 4))
        assertNull(
            EventPluginBindingAtOffsetFinder.find(
                "prefix '(click.stop)' = value",
                10,
            ),
        )
        assertNull(EventPluginBindingAtOffsetFinder.find("(click.stop > x)=\"x\"", 5))
    }

    @Test
    fun `binding finder does not scan beyond maximum binding length`() {
        val text = "(" + "a".repeat(200) + ")=\"x\""

        assertNull(
            EventPluginBindingAtOffsetFinder.find(
                text,
                text.length / 2,
            ),
        )
    }

    @Test
    fun `extended key events accept standard named symbol function and modified keys`() {
        listOf(
            "keydown.enter",
            "keyup.ESC",
            "keydown.a",
            "keydown.7",
            "keydown.?",
            "keydown.f1",
            "keydown.f20",
            "keyup.shift.control.arrowdown",
            "keydown.code.keya",
            "keydown.code.digit0",
            "keydown.code.numpad9",
            "keydown.code.f24",
            "keydown.code.altright",
        ).forEach { event ->
            assertTrue("Expected valid key event: $event", AngularExtendedKeyEventSupport.isValid(event))
        }
    }

    @Test
    fun `extended key events reject malformed modifiers prefixes and key names`() {
        listOf(
            "click.enter",
            "keydown",
            "keydown.",
            "keydown.f21",
            "keydown.shift.shift.enter",
            "keydown.unknown.enter",
            "keydown.code",
            "keydown.code.keyaa",
            "keydown.code.digit10",
            "keydown.code.numpad10",
            "keydown.code.f25",
        ).forEach { event ->
            assertFalse("Expected invalid key event: $event", AngularExtendedKeyEventSupport.isValid(event))
        }
    }
}
