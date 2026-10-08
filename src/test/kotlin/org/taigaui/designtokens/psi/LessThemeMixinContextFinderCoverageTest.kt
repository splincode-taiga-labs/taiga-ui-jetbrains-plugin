package org.taigaui.designtokens.psi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LessThemeMixinContextFinderCoverageTest {
    @Test
    fun `recognizes every supported theme mixin spelling`() {
        val cases =
            mapOf(
                ".light() { color: red; }" to ".light()",
                ".dark { color: red; }" to ".dark()",
                ".tui-theme-light() { color: red; }" to ".light()",
                ".tui-theme-dark { color: red; }" to ".dark()",
                ".tui-theme-variables() { color: red; }" to ".tui-theme-variables()",
                "@mixin light { color: red; }" to ".light()",
                "@mixin TUI-THEME-DARK() { color: red; }" to ".dark()",
            )

        cases.forEach { (source, expected) ->
            assertEquals(
                expected,
                LessThemeMixinContextFinder.find(
                    source,
                    source.indexOf("color"),
                ),
            )
        }
    }

    @Test
    fun `uses innermost enclosing theme before offset`() {
        val source =
            """
            .light() {
                color: red;

                .dark() {
                    color: blue;
                }

                background: white;
            }
            """.trimIndent()

        assertEquals(
            ".dark()",
            LessThemeMixinContextFinder.find(
                source,
                source.indexOf("color: blue"),
            ),
        )
        assertEquals(
            ".light()",
            LessThemeMixinContextFinder.find(
                source,
                source.indexOf("background"),
            ),
        )
    }

    @Test
    fun `brace scanner ignores comments quoted braces and escaped quotes`() {
        val source =
            """
            .light() {
                /* } */
                // }
                content: "quoted } and escaped \" }";
                other: 'single } and escaped \' }';
                .nested {
                    value: red;
                }
                color: blue;
            }
            """.trimIndent()

        assertEquals(
            ".light()",
            LessThemeMixinContextFinder.find(
                source,
                source.indexOf("color: blue"),
            ),
        )
    }

    @Test
    fun `returns null outside theme and for unterminated block`() {
        val outside = ".light() { color: red; } .button { color: blue; }"

        assertNull(
            LessThemeMixinContextFinder.find(
                outside,
                outside.lastIndexOf("color"),
            ),
        )

        val unterminated = ".dark() { color: red;"

        assertNull(
            LessThemeMixinContextFinder.find(
                unterminated,
                unterminated.indexOf("color"),
            ),
        )
    }
}
