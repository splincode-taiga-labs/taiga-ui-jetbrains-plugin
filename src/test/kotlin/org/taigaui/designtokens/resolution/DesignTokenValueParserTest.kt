package org.taigaui.designtokens.resolution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignTokenValueParserTest {
    @Test
    fun `keeps terminal value as one text part`() {
        val parsed = parse("rgb(1 2 3 / 50%)")

        assertEquals(
            listOf(DesignTokenValuePart.Text("rgb(1 2 3 / 50%)")),
            parsed.parts,
        )
    }

    @Test
    fun `parses multiple references inside compound value`() {
        val parsed = parse("calc(var(--tui-size) * 2) solid var(--tui-color)")

        assertEquals(4, parsed.parts.size)
        assertEquals("--tui-size", (parsed.parts[1] as DesignTokenValuePart.Reference).name)
        assertEquals("--tui-color", (parsed.parts[3] as DesignTokenValuePart.Reference).name)
    }

    @Test
    fun `parses nested fallback with commas`() {
        val parsed = parse("var(--tui-color, rgb(1, 2, var(--tui-blue, 3)))")
        val reference = parsed.parts.single() as DesignTokenValuePart.Reference
        val fallback = requireNotNull(reference.fallback)

        assertEquals("rgb(1, 2, var(--tui-blue, 3))", fallback.rawValue)
        assertEquals("--tui-blue", (fallback.parts[1] as DesignTokenValuePart.Reference).name)
        assertEquals("3", requireNotNull((fallback.parts[1] as DesignTokenValuePart.Reference).fallback).rawValue)
    }

    @Test
    fun `supports empty fallback`() {
        val parsed = parse("var(--tui-optional,)")
        val reference = parsed.parts.single() as DesignTokenValuePart.Reference

        assertEquals("", requireNotNull(reference.fallback).rawValue)
        assertTrue(requireNotNull(reference.fallback).parts.isEmpty())
    }

    @Test
    fun `ignores var text inside quoted strings`() {
        val parsed = parse("'var(--tui-missing)' var(--tui-real)")

        assertEquals(2, parsed.parts.size)
        assertEquals(
            "'var(--tui-missing)' ",
            (parsed.parts.first() as DesignTokenValuePart.Text).value,
        )
        assertEquals("--tui-real", (parsed.parts.last() as DesignTokenValuePart.Reference).name)
    }

    @Test
    fun `ignores var text inside comments`() {
        val parsed = parse("/* var(--tui-missing) */ var(--tui-real)")

        assertEquals(2, parsed.parts.size)
        assertEquals("--tui-real", (parsed.parts.last() as DesignTokenValuePart.Reference).name)
    }

    @Test
    fun `matches var function case insensitively`() {
        val parsed = parse("VAR(--tui-color)")

        assertEquals("--tui-color", (parsed.parts.single() as DesignTokenValuePart.Reference).name)
    }

    @Test
    fun `does not parse var as part of another identifier`() {
        val parsed = parse("myvar(--tui-color)")

        assertEquals(
            listOf(DesignTokenValuePart.Text("myvar(--tui-color)")),
            parsed.parts,
        )
    }

    @Test
    fun `rejects unterminated var expression`() {
        val result = DesignTokenValueParser.parse("var(--tui-color")

        assertTrue(result is DesignTokenValueParseResult.Invalid)
    }

    @Test
    fun `rejects reference without custom property name`() {
        val result = DesignTokenValueParser.parse("var(color, red)")

        assertTrue(result is DesignTokenValueParseResult.Invalid)
    }

    @Test
    fun `handles quoted commas comments nested functions and trimmed fallback`() {
        val parsed =
            parse(
                """var(--tui-value,  fn("a,b", (1, 2)) /* , ignored */ var(--tui-next, blue)  )""",
            )
        val reference = parsed.parts.single() as DesignTokenValuePart.Reference
        val fallback = requireNotNull(reference.fallback)

        assertEquals(
            """fn("a,b", (1, 2)) /* , ignored */ var(--tui-next, blue)""",
            fallback.rawValue,
        )
        assertEquals(
            "--tui-next",
            (fallback.parts.last() as DesignTokenValuePart.Reference).name,
        )
    }

    @Test
    fun `rejects unterminated quoted strings and comments`() {
        listOf(
            "'unterminated",
            "\"unterminated",
            "'escaped\\",
            "/* unterminated",
            "var(--tui-value, /* unterminated)",
        ).forEach { value ->
            assertTrue(
                "Expected invalid parse for: $value",
                DesignTokenValueParser.parse(value) is DesignTokenValueParseResult.Invalid,
            )
        }
    }

    @Test
    fun `rejects malformed custom property names`() {
        listOf(
            "var(-x)",
            "var(--)",
            "var(--tui value)",
            "var(--tui value)",
            "var(--tui(value))",
        ).forEach { value ->
            assertTrue(
                "Expected invalid parse for: $value",
                DesignTokenValueParser.parse(value) is DesignTokenValueParseResult.Invalid,
            )
        }
    }

    @Test
    fun `does not recognize var after identifier characters`() {
        listOf(
            "xvar(--tui-color)",
            "1var(--tui-color)",
            "_var(--tui-color)",
            "-var(--tui-color)",
        ).forEach { value ->
            assertEquals(
                listOf(DesignTokenValuePart.Text(value)),
                parse(value).parts,
            )
        }
    }

    @Test
    fun `top level comma scanner skips quoted commented and nested commas`() {
        val cases =
            listOf(
                """var(--tui-value, "a,b")""",
                "var(--tui-value, /* a,b */ blue)",
                "var(--tui-value, fn(a,b))",
                "var(--tui-value, (a,b))",
            )

        cases.forEach { value ->
            val reference = parse(value).parts.single() as DesignTokenValuePart.Reference

            assertEquals("--tui-value", reference.name)
            assertTrue(requireNotNull(reference.fallback).rawValue.isNotBlank())
        }
    }

    @Test
    fun `closing parenthesis scanner skips quotes comments and nested functions`() {
        val parsed =
            parse(
                """var(--tui-value, fn(")", /* ) */ nested((x)))) trailing""",
            )
        val reference = parsed.parts.first() as DesignTokenValuePart.Reference

        assertEquals("--tui-value", reference.name)
        assertEquals("""fn(")", /* ) */ nested((x)))""", requireNotNull(reference.fallback).rawValue)
        assertEquals(" trailing", (parsed.parts.last() as DesignTokenValuePart.Text).value)
    }

    @Test
    fun `quoted scanner handles escaped quote before closing quote`() {
        val parsed = parse("""'a\'b' var(--tui-real)""")

        assertEquals(2, parsed.parts.size)
        assertEquals("--tui-real", (parsed.parts.last() as DesignTokenValuePart.Reference).name)
    }

    @Test
    fun `fallback trimming removes both leading and trailing whitespace`() {
        val reference =
            parse("var(--tui-value,   blue   )")
                .parts
                .single() as DesignTokenValuePart.Reference

        assertEquals("blue", requireNotNull(reference.fallback).rawValue)
    }

    @Test
    fun `rejects custom property names containing comma or parentheses`() {
        listOf(
            "var(tui-bad)",
            "var(--tui bad)",
            "var(--tui(bad))",
        ).forEach { value ->
            assertTrue(
                "Expected invalid parse for: $value",
                DesignTokenValueParser.parse(value) is DesignTokenValueParseResult.Invalid,
            )
        }
    }

    @Test
    fun `keeps absent fallback distinct from empty fallback`() {
        val withoutFallback =
            (parse("var(--tui-color)").parts.single() as DesignTokenValuePart.Reference).fallback
        val withEmptyFallback =
            (parse("var(--tui-color,)").parts.single() as DesignTokenValuePart.Reference).fallback

        assertNull(withoutFallback)
        assertEquals("", requireNotNull(withEmptyFallback).rawValue)
    }

    private fun parse(value: String): ParsedDesignTokenValue =
        when (val result = DesignTokenValueParser.parse(value)) {
            is DesignTokenValueParseResult.Parsed -> result.value
            is DesignTokenValueParseResult.Invalid -> error(result.message)
        }
}
