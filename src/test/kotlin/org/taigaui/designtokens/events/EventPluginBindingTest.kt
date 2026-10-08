package org.taigaui.designtokens.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventPluginBindingTest {
    @Test
    fun `parses composed event modifiers`() {
        val binding = requireNotNull(EventPluginBinding.parse("(click.zoneless.capture)"))

        assertEquals("click", binding.event)
        assertEquals(listOf("zoneless", "capture"), binding.modifiers.map(EventPluginModifier::source))
    }

    @Test
    fun `keeps angular pseudo event segments in base event`() {
        val binding = requireNotNull(EventPluginBinding.parse("(keydown.enter.stop)"))

        assertEquals("keydown.enter", binding.event)
        assertEquals(listOf("stop"), binding.modifiers.map(EventPluginModifier::source))
    }

    @Test
    fun `supports timed modifiers`() {
        val binding = requireNotNull(EventPluginBinding.parse("(click.debounce~300ms.once)"))

        assertEquals(listOf("debounce~300ms", "once"), binding.modifiers.map(EventPluginModifier::source))
    }

    @Test
    fun `recognizes global event plugin bindings`() {
        val binding = requireNotNull(GlobalEventPluginBindingSupport.parse("(visualViewport>resize)"))

        assertEquals("visualViewport", binding.target)
        assertEquals("resize", binding.event)
        assertTrue(GlobalEventPluginBindingSupport.isValid("(document.body>click)"))
        assertTrue(GlobalEventPluginBindingSupport.isValid("(visualViewport>resize.zoneless)"))
    }

    @Test
    fun `rejects malformed global event plugin bindings`() {
        assertFalse(GlobalEventPluginBindingSupport.isValid("(visualViewport>)"))
        assertFalse(GlobalEventPluginBindingSupport.isValid("(>resize)"))
        assertFalse(GlobalEventPluginBindingSupport.isValid("(visualViewport>>resize)"))
        assertFalse(GlobalEventPluginBindingSupport.isValid("(visual viewport>resize)"))
    }

    @Test
    fun `rejects unknown and duplicate taiga modifiers`() {
        assertNull(EventPluginBinding.parse("(click.captre)"))
        assertNull(EventPluginBinding.parse("(click.zoneless.captre)"))
        assertNull(EventPluginBinding.parse("(click.zoneless.zoneless)"))
    }

    @Test
    fun `recognizes valid angular extended key events`() {
        assertTrue(AngularExtendedKeyEventSupport.isValid("keydown.enter"))
        assertTrue(AngularExtendedKeyEventSupport.isValid("keydown.shift.enter"))
        assertTrue(AngularExtendedKeyEventSupport.isValid("keyup.escape"))
        assertTrue(AngularExtendedKeyEventSupport.isValid("keydown.code.keyA"))
        assertTrue(AngularExtendedKeyEventSupport.isValid("keydown.control.shift.f12"))
    }

    @Test
    fun `rejects invalid angular extended key events`() {
        assertFalse(AngularExtendedKeyEventSupport.isValid("click"))
        assertFalse(AngularExtendedKeyEventSupport.isValid("keydown.foo"))
        assertFalse(AngularExtendedKeyEventSupport.isValid("keydown.shift"))
        assertFalse(AngularExtendedKeyEventSupport.isValid("keydown.shift.shift.enter"))
        assertFalse(AngularExtendedKeyEventSupport.isValid("keydown.code.foo"))
    }

    @Test
    fun `parses every built in modifier behavior`() {
        val expected =
            listOf(
                "capture",
                "once",
                "passive",
                "prevent",
                "self",
                "silent",
                "zoneless",
                "stop",
                "debounce~250ms",
                "throttle~2s",
            )

        expected.forEach { source ->
            val modifier = requireNotNull(EventPluginModifier.parse(source))

            assertEquals(source, modifier.source)
            assertTrue(modifier.description.isNotBlank())
            assertTrue(modifier.behavior.isNotBlank())
        }

        assertNull(EventPluginModifier.parse("debounce~ms"))
        assertNull(EventPluginModifier.parse("throttle~1m"))
        assertNull(EventPluginModifier.parse("unknown"))
    }

    @Test
    fun `rejects malformed event binding boundaries and empty base event`() {
        assertNull(EventPluginBinding.parse("click.stop)"))
        assertNull(EventPluginBinding.parse("(click.stop"))
        assertNull(EventPluginBinding.parse("()"))
        assertNull(EventPluginBinding.parse("(stop)"))
    }

    @Test
    fun `global binding supports identifiers dots dollars and modifier-like event suffixes`() {
        val binding = requireNotNull(GlobalEventPluginBindingSupport.parse("(window.visualViewport$>resize.zoneless)"))

        assertEquals("window.visualViewport$", binding.target)
        assertEquals("resize.zoneless", binding.event)
        assertEquals("(window.visualViewport$>resize.zoneless)", binding.source)
    }

    @Test
    fun `global binding rejects invalid target and event characters`() {
        assertNull(GlobalEventPluginBindingSupport.parse("window>resize"))
        assertNull(GlobalEventPluginBindingSupport.parse("(window>resize"))
        assertNull(GlobalEventPluginBindingSupport.parse("(window>resize)extra"))
        assertNull(GlobalEventPluginBindingSupport.parse("(1window>resize)"))
        assertNull(GlobalEventPluginBindingSupport.parse("(window>resize space)"))
        assertNull(GlobalEventPluginBindingSupport.parse("(window>resize>again)"))
    }

    @Test
    fun `finds unknown taiga modifier typos`() {
        val text =
            """
            <button (click.captre)="method()"></button>
            <button (click.zoneless.captre)="method()"></button>
            <button (keydown.enter.stop)="method()"></button>
            """.trimIndent()

        val problems = EventPluginUnknownModifierFinder.findAll(text)

        assertEquals(listOf("captre", "captre"), problems.map(EventPluginUnknownModifier::modifier))
        problems.forEach { problem ->
            assertEquals(problem.modifier, text.substring(problem.startOffset, problem.endOffset))
        }
    }

    @Test
    fun `finds duplicate taiga modifiers`() {
        val text =
            """
            <button (click.zoneless.zoneless)="method()"></button>
            <button (click.debounce~100ms.debounce~200ms)="method()"></button>
            <button (keydown.enter.stop)="method()"></button>
            """.trimIndent()

        val problems = EventPluginDuplicateModifierFinder.findAll(text)

        assertEquals(listOf("zoneless", "debounce~200ms"), problems.map(EventPluginDuplicateModifier::modifier))
        problems.forEach { problem ->
            assertEquals(problem.modifier, text.substring(problem.startOffset, problem.endOffset))
        }
    }

    @Test
    fun `ignores bindings without taiga modifiers`() {
        assertNull(EventPluginBinding.parse("(click)"))
        assertNull(EventPluginBinding.parse("class"))
    }

    @Test
    fun `combines modifier behavior`() {
        val binding = requireNotNull(EventPluginBinding.parse("(click.zoneless.capture)"))

        assertTrue(binding.combinedBehavior.contains("Handles click"))
        assertTrue(binding.combinedBehavior.contains("outside Angular's NgZone"))
        assertTrue(binding.combinedBehavior.contains("capture phase"))
    }

    @Test
    fun `finds binding under pointer`() {
        val text = """<button (click.zoneless.capture)="method()"></button>"""
        val offset = text.indexOf("zoneless") + 2
        val reference = requireNotNull(EventPluginBindingAtOffsetFinder.find(text, offset))

        assertEquals("(click.zoneless.capture)", reference.binding.source)
        assertEquals("click", reference.binding.event)
        assertEquals(text.indexOf("(click"), reference.startOffset)
        assertEquals(text.indexOf(")=\"") + 1, reference.endOffset)
    }

    @Test
    fun `finder requires angular attribute assignment`() {
        val text = "const value = (click.stop)"
        val offset = text.indexOf("stop")

        assertNull(EventPluginBindingAtOffsetFinder.find(text, offset))
    }

    @Test
    fun `offers host modifiers after a complete event`() {
        val context = requireNotNull(HostEventPluginCompletionContext.parse("(click."))

        assertEquals("", context.prefix)
        assertTrue(context.usedModifierIdentities.isEmpty())
    }

    @Test
    fun `filters host completion by current modifier prefix`() {
        val context = requireNotNull(HostEventPluginCompletionContext.parse("(click.zone"))

        assertEquals("zone", context.prefix)
    }

    @Test
    fun `tracks used host modifiers`() {
        val context = requireNotNull(HostEventPluginCompletionContext.parse("(click.zoneless.capture."))

        assertEquals(setOf("zoneless", "capture"), context.usedModifierIdentities)
    }

    @Test
    fun `supports host completion after angular extended key event`() {
        assertTrue(HostEventPluginCompletionContext.parse("(keydown.enter.") != null)
        assertTrue(HostEventPluginCompletionContext.parse("(keydown.shift.enter.") != null)
        assertNull(HostEventPluginCompletionContext.parse("(keydown."))
        assertNull(HostEventPluginCompletionContext.parse("(keydown.shift."))
    }

    @Test
    fun `does not continue invalid or duplicate host modifier chains`() {
        assertNull(HostEventPluginCompletionContext.parse("(click.captre."))
        assertNull(HostEventPluginCompletionContext.parse("(click.zoneless.zoneless."))
    }
}
