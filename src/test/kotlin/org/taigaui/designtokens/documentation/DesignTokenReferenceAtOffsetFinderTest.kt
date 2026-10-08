package org.taigaui.designtokens.documentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesignTokenReferenceAtOffsetFinderTest {
    @Test
    fun `finds token only while caret is inside its name`() {
        val text = ".button { color: var(--tui-text-primary); }"
        val start = text.indexOf(TOKEN)

        listOf(start, start + 5, start + TOKEN.length - 1).forEach { offset ->
            assertEquals(TOKEN, DesignTokenReferenceAtOffsetFinder.find(text, offset)?.name)
        }
        assertNull(DesignTokenReferenceAtOffsetFinder.find(text, start + TOKEN.length))
    }

    @Test
    fun `finds all Taiga references used by a stylesheet`() {
        val text =
            """
            .button {
                color: var(--tui-text-primary);
                background: var(--tui-background-base, var(--tui-background-neutral-1));
                content: "var(--tui-ignored-string)";
                /* border-color: var(--tui-ignored-comment); */
            }
            """.trimIndent()

        assertEquals(
            listOf(
                "--tui-text-primary",
                "--tui-background-base",
                "--tui-background-neutral-1",
            ),
            DesignTokenReferenceAtOffsetFinder.findAll(text).map(DesignTokenReferenceAtOffset::name),
        )
    }

    @Test
    fun `finds reference with whitespace and uppercase var function`() {
        val text = ".button { color: VAR(  $TOKEN  , red); }"
        val offset = text.indexOf(TOKEN) + 3
        val reference = requireNotNull(DesignTokenReferenceAtOffsetFinder.find(text, offset))

        assertEquals(TOKEN, reference.name)
        assertEquals(text.indexOf(TOKEN), reference.startOffset)
        assertEquals(text.indexOf(TOKEN) + TOKEN.length, reference.endOffset)
    }

    @Test
    fun `finds nested reference inside fallback`() {
        val nested = "--tui-fallback"
        val text = "color: var(--tui-missing, rgb(1 2 3 / var($nested)));"
        val reference =
            DesignTokenReferenceAtOffsetFinder.find(
                text,
                text.indexOf(nested) + 4,
            )

        assertEquals(nested, reference?.name)
    }

    @Test
    fun `finds the reference under caret when value contains several vars`() {
        val second = "--tui-border"
        val text = "box-shadow: var($TOKEN) 0 0 0 1px var($second);"

        assertEquals(
            second,
            DesignTokenReferenceAtOffsetFinder.find(text, text.indexOf(second) + 2)?.name,
        )
    }

    @Test
    fun `ignores var text inside strings and comments`() {
        val text =
            """
            .button::before {
                content: "var($TOKEN)";
                /* color: var($TOKEN); */
            }
            """.trimIndent()

        indicesOf(text, TOKEN).forEach { start ->
            assertNull(DesignTokenReferenceAtOffsetFinder.find(text, start + 2))
        }
    }

    @Test
    fun `ignores non Taiga custom properties`() {
        val text = "color: var(--company-color);"

        assertNull(
            DesignTokenReferenceAtOffsetFinder.find(
                text,
                text.indexOf("--company-color") + 2,
            ),
        )
    }

    @Test
    fun `ignores token name outside var expression`() {
        val text = ".button { $TOKEN: red; color: $TOKEN; }"

        indicesOf(text, TOKEN).forEach { start ->
            assertNull(DesignTokenReferenceAtOffsetFinder.find(text, start + 3))
        }
    }

    @Test
    fun `does not return reference for malformed unterminated var`() {
        val text = "color: var($TOKEN"

        assertNull(
            DesignTokenReferenceAtOffsetFinder.find(
                text,
                text.indexOf(TOKEN) + 3,
            ),
        )
    }

    @Test
    fun `scanner skips nested parentheses quotes and comments in first var argument`() {
        val text =
            """color: var(fn(")", /* ) */ nested((1, 2))) --tui-not-a-reference, $TOKEN);"""

        assertNull(
            DesignTokenReferenceAtOffsetFinder.find(
                text,
                text.indexOf("--tui-not-a-reference") + 4,
            ),
        )
        assertNull(
            DesignTokenReferenceAtOffsetFinder.find(
                text,
                text.lastIndexOf(TOKEN) + 4,
            ),
        )
    }

    @Test
    fun `scanner handles escaped and unterminated quotes without leaking references`() {
        val escaped = """content: "escaped \" var($TOKEN)"; color: var(--tui-real);"""

        assertNull(
            DesignTokenReferenceAtOffsetFinder.find(
                escaped,
                escaped.indexOf(TOKEN) + 3,
            ),
        )
        val realReference =
            DesignTokenReferenceAtOffsetFinder.find(
                escaped,
                escaped.indexOf("--tui-real") + 3,
            )

        assertEquals("--tui-real", realReference?.name)

        val unterminated = """content: "unterminated var($TOKEN)"""

        assertNull(
            DesignTokenReferenceAtOffsetFinder.find(
                unterminated,
                unterminated.indexOf(TOKEN) + 3,
            ),
        )
    }

    @Test
    fun `scanner handles unterminated block comment safely`() {
        val text = "color: red; /* var($TOKEN)"

        assertNull(
            DesignTokenReferenceAtOffsetFinder.find(
                text,
                text.indexOf(TOKEN) + 3,
            ),
        )
        assertEquals(emptyList<DesignTokenReferenceAtOffset>(), DesignTokenReferenceAtOffsetFinder.findAll(text))
    }

    @Test
    fun `accepts taiga token digits underscores and hyphens`() {
        val token = "--tui-a_1-b2"
        val text = "color: var($token);"

        assertEquals(
            token,
            DesignTokenReferenceAtOffsetFinder.find(text, text.indexOf(token) + 4)?.name,
        )
    }

    @Test
    fun `returns null for offset outside document`() {
        assertNull(DesignTokenReferenceAtOffsetFinder.find("var($TOKEN)", -1))
        assertNull(DesignTokenReferenceAtOffsetFinder.find("var($TOKEN)", 1000))
    }

    private fun indicesOf(
        text: String,
        value: String,
    ): List<Int> =
        buildList {
            var index = text.indexOf(value)

            while (index >= 0) {
                add(index)
                index = text.indexOf(value, startIndex = index + value.length)
            }
        }

    private companion object {
        const val TOKEN = "--tui-text-primary"
    }
}

class DesignTokenNameMatcherTest {
    @Test
    fun `suggests a close token typo`() {
        assertEquals(
            "--tui-text-primary",
            DesignTokenNameMatcher.closest(
                "--tui-text-primari",
                listOf("--tui-text-primary", "--tui-text-secondary"),
            ),
        )
    }

    @Test
    fun `uses typo match when there are no prefix variants`() {
        assertEquals(
            listOf("--tui-text-primary"),
            DesignTokenNameMatcher.suggestions(
                "--tui-text-primari",
                listOf("--tui-text-primary", "--tui-text-secondary"),
            ),
        )
    }

    @Test
    fun `suggests elevation variants for incomplete elevation token`() {
        assertEquals(
            listOf(
                "--tui-background-elevation-1",
                "--tui-background-elevation-2",
                "--tui-background-elevation-3",
            ),
            DesignTokenNameMatcher.suggestions(
                "--tui-background-elevation",
                listOf(
                    "--tui-background-base",
                    "--tui-background-elevation-3",
                    "--tui-background-elevation-1",
                    "--tui-background-elevation-2",
                ),
            ),
        )
    }

    @Test
    fun `suggests border variants for incomplete border token`() {
        assertEquals(
            listOf(
                "--tui-border-hover",
                "--tui-border-normal",
            ),
            DesignTokenNameMatcher.suggestions(
                "--tui-border",
                listOf(
                    "--tui-border-normal",
                    "--tui-text-primary",
                    "--tui-border-hover",
                ),
            ),
        )
    }

    @Test
    fun `suggests variants while the last token segment is still partial`() {
        assertEquals(
            listOf(
                "--tui-font-text",
                "--tui-font-text-m",
                "--tui-font-text-s",
                "--tui-font-text-xs",
            ),
            DesignTokenNameMatcher.suggestions(
                "--tui-font-te",
                listOf(
                    "--tui-font-text-xs",
                    "--tui-font-heading",
                    "--tui-font-text-s",
                    "--tui-font-text",
                    "--tui-font-text-m",
                ),
            ),
        )
    }

    @Test
    fun `suggests a longer token when its suffix is only partially typed`() {
        assertEquals(
            listOf(
                "--tui-text-secondary",
                "--tui-text-secondary-hover",
            ),
            DesignTokenNameMatcher.suggestions(
                "--tui-text-secon",
                listOf(
                    "--tui-text-primary",
                    "--tui-text-secondary-hover",
                    "--tui-text-secondary",
                ),
            ),
        )
    }

    @Test
    fun `falls back to typo correction when no token starts with the unknown name`() {
        assertEquals(
            listOf("--tui-font-text-xs"),
            DesignTokenNameMatcher.suggestions(
                "--tui-font-text-xs2",
                listOf(
                    "--tui-font-text-xs",
                    "--tui-font-text-s",
                    "--tui-font-text-m",
                ),
            ),
        )
    }

    @Test
    fun `does not suggest a distant token`() {
        assertNull(
            DesignTokenNameMatcher.closest(
                "--tui-completely-unknown-token",
                listOf("--tui-text-primary", "--tui-radius-m"),
            ),
        )
    }

    @Test
    fun `does not guess between equally close tokens`() {
        assertNull(
            DesignTokenNameMatcher.closest(
                "--tui-color-fed",
                listOf("--tui-color-red", "--tui-color-bed"),
            ),
        )
    }
}
