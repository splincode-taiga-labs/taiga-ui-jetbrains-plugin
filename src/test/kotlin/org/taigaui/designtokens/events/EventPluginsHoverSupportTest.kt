package org.taigaui.designtokens.events

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import javax.swing.JPanel

class EventPluginsHoverSupportTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "node_modules/@angular/core/package.json",
            """{"name":"@angular/core","version":"22.0.0","types":"index.d.ts"}""",
        )
        myFixture.addFileToProject(
            "node_modules/@angular/core/index.d.ts",
            """
            export interface DirectiveMetadata {
                selector?: string;
                host?: Record<string, string>;
            }
            export declare function Directive(metadata: DirectiveMetadata): ClassDecorator;
            """.trimIndent(),
        )
    }

    fun testFindsHtmlEventPluginBindingAtAnyOffsetInsideAttributeName() {
        val text = "(click.prevent.stop)   = \"onClick()\""

        listOf(
            text.indexOf("click"),
            text.indexOf("prevent") + 2,
            text.indexOf("stop") + 1,
            text.indexOf(')'),
        ).forEach { offset ->
            val found = EventPluginBindingAtOffsetFinder.find(text, offset)

            assertNotNull(found)
            assertEquals("(click.prevent.stop)", found?.binding?.source)
            assertEquals(0, found?.startOffset)
            assertEquals(text.indexOf(')') + 1, found?.endOffset)
        }
    }

    fun testRejectsInvalidOffsetsBoundariesAndAttributesWithoutValue() {
        assertNull(EventPluginBindingAtOffsetFinder.find("", 0))
        assertNull(EventPluginBindingAtOffsetFinder.find("(click.prevent)=\"x\"", -1))
        assertNull(EventPluginBindingAtOffsetFinder.find("(click.prevent)=\"x\"", 100))
        assertNull(EventPluginBindingAtOffsetFinder.find("(click.prevent)", 5))
        assertNull(EventPluginBindingAtOffsetFinder.find("< (click.prevent)=\"x\"", 0))
        assertNull(EventPluginBindingAtOffsetFinder.find("(click.unknown)=\"x\"", 5))
    }

    fun testStopsSearchingAtBindingBoundariesAndMaximumLength() {
        val separated = "(click.prevent)=\"x\" other (keydown.stop)=\"y\""
        val secondOffset = separated.indexOf("keydown") + 2

        assertEquals(
            "(keydown.stop)",
            EventPluginBindingAtOffsetFinder.find(separated, secondOffset)?.binding?.source,
        )

        val veryLongEvent = "(" + "a".repeat(170) + ".stop)=\"x\""
        assertNull(
            EventPluginBindingAtOffsetFinder.find(
                veryLongEvent,
                veryLongEvent.indexOf("stop"),
            ),
        )
    }

    fun testFallsBackToAngularHostMetadata() {
        val file =
            myFixture.addFileToProject(
                "src/directive.ts",
                """
                import {Directive} from '@angular/core';

                @Directive({
                    selector: '[example]',
                    host: {'(keydown.enter.stop)': 'onKey()'},
                })
                export class ExampleDirective {}
                """.trimIndent(),
            )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        val offset = myFixture.file.text.indexOf("enter") + 2
        val found =
            EventPluginBindingAtOffsetFinder.find(
                file = myFixture.file,
                text = myFixture.file.text,
                offset = offset,
            )

        assertNotNull(found)
        assertEquals("(keydown.enter.stop)", found?.binding?.source)
        assertEquals(
            "(keydown.enter.stop)",
            myFixture.file.text.substring(
                requireNotNull(found).startOffset,
                found.endOffset,
            ),
        )
    }

    fun testPopupPanelRendersEscapedEventDocumentationAndModifierBehavior() {
        val binding = requireNotNull(EventPluginBinding.parse("(custom<event.prevent.stop)"))
        val panelClass =
            Class.forName("org.taigaui.designtokens.events.EventPluginsHoverPopupPanel")
        val constructor =
            panelClass
                .getDeclaredConstructor(EventPluginBinding::class.java)
                .apply { isAccessible = true }
        val panel = constructor.newInstance(binding) as JPanel
        val label = panel.components.filterIsInstance<JBLabel>().single()
        val html = label.text

        assertTrue(html.contains("custom&lt;event"))
        assertTrue(html.contains("prevent"))
        assertTrue(html.contains("stop"))
        assertTrue(html.contains("Combined behavior"))
        assertTrue(html.contains("@taiga-ui/event-plugins"))
        assertFalse(html.contains("custom<event"))
    }
}
