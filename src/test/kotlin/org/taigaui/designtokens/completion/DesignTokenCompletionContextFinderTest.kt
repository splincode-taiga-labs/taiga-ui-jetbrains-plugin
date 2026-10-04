package org.taigaui.designtokens.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesignTokenCompletionContextFinderTest {
    @Test
    fun `finds taiga token prefix in first var argument`() {
        assertPrefix(
            ".demo { color: var(--tui-te<caret>); }",
            "--tui-te",
        )
    }

    @Test
    fun `finds complete taiga token name in first var argument`() {
        assertPrefix(
            ".demo { color: var(--tui-text-primary<caret>); }",
            "--tui-text-primary",
        )
    }

    @Test
    fun `allows whitespace before token prefix`() {
        assertPrefix(
            ".demo { color: var(  --tui-<caret>); }",
            "--tui-",
        )
    }

    @Test
    fun `finds nested var first argument`() {
        assertPrefix(
            ".demo { color: var(--brand, var(--tui-text-<caret>)); }",
            "--tui-text-",
        )
    }

    @Test
    fun `matches var case insensitively`() {
        assertPrefix(
            ".demo { color: VAR(--tui-text-<caret>); }",
            "--tui-text-",
        )
    }

    @Test
    fun `handles nested functions quotes comments and commas in first argument`() {
        assertNoContext(
            ".demo { color: var(calc(1 + fn('a,b', /* x,y */ 2)) + --tui-value<caret>, red); }",
        )
        assertNoContext(
            ".demo { color: var( /* comment */ --tui-value<caret>, fn(1, 2)); }",
        )
    }

    @Test
    fun `finds prefix in unterminated var function`() {
        assertPrefix(
            ".demo { color: var(--tui-text-<caret>",
            "--tui-text-",
        )
    }

    @Test
    fun `ignores second var argument`() {
        assertNoContext(".demo { color: var(--brand, --tui-te<caret>); }")
    }

    @Test
    fun `ignores token declarations`() {
        assertNoContext(":root { --tui-te<caret>: red; }")
    }

    @Test
    fun `ignores non taiga custom properties`() {
        assertNoContext(".demo { color: var(--brand-te<caret>); }")
    }

    @Test
    fun `ignores var text inside comments`() {
        assertNoContext(".demo { /* var(--tui-te<caret>) */ color: red; }")
    }

    @Test
    fun `ignores var when it is part of another identifier`() {
        listOf(
            "myvar(--tui-te<caret>)",
            "_var(--tui-te<caret>)",
            "-var(--tui-te<caret>)",
            "1var(--tui-te<caret>)",
        ).forEach(::assertNoContext)
    }

    @Test
    fun `handles escaped and unterminated quoted text safely`() {
        assertNoContext(
            """.demo::before { content: "escaped \" var(--tui-te<caret>)"; }""",
        )
        assertNoContext(
            """.demo::before { content: "unterminated var(--tui-te<caret>)""",
        )
    }

    @Test
    fun `handles unterminated comment safely`() {
        assertNoContext(
            ".demo { /* unterminated var(--tui-te<caret>)",
        )
    }

    @Test
    fun `scanner skips nested parentheses and escaped quoted text before active var`() {
        assertPrefix(
            """fn((1, 2), "escaped \" value") var(--tui-active<caret>)""",
            "--tui-active",
        )
    }

    @Test
    fun `scanner ignores unterminated single quote before token text`() {
        assertNoContext(
            """.demo { content: 'unterminated var(--tui-te<caret>)""",
        )
    }

    @Test
    fun `scanner accepts digits underscores and hyphens in prefix`() {
        assertPrefix(
            ".demo { color: var(--tui-a_1-b2<caret>); }",
            "--tui-a_1-b2",
        )
    }

    @Test
    fun `caret immediately after var opening has no taiga prefix`() {
        assertNoContext(".demo { color: var(<caret>--tui-text); }")
    }

    @Test
    fun `rejects invalid offsets`() {
        assertNull(DesignTokenCompletionContextFinder.find("var(--tui-)", -1))
        assertNull(DesignTokenCompletionContextFinder.find("var(--tui-)", 100))
    }

    @Test
    fun `stops first argument at closing parenthesis and comma`() {
        assertNoContext(".demo { color: var(--tui-text, --tui-second<caret>); }")
        assertNoContext(".demo { color: var(--tui-text) --tui-after<caret>; }")
    }

    @Test
    fun `ignores var text inside strings`() {
        assertNoContext(".demo::before { content: 'var(--tui-te<caret>)'; }")
    }

    private fun assertPrefix(
        source: String,
        expectedPrefix: String,
    ) {
        val (text, offset) = source.withCaret()

        assertEquals(
            expectedPrefix,
            DesignTokenCompletionContextFinder.find(text, offset)?.prefix,
        )
    }

    private fun assertNoContext(source: String) {
        val (text, offset) = source.withCaret()

        assertNull(DesignTokenCompletionContextFinder.find(text, offset))
    }

    private fun String.withCaret(): Pair<String, Int> {
        val offset = indexOf(CARET)

        require(offset >= 0)

        return replace(CARET, "") to offset
    }

    private companion object {
        const val CARET = "<caret>"
    }
}
