package org.taigaui.designtokens.documentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.taigaui.designtokens.index.DesignTokenOrigin
import org.taigaui.designtokens.index.DesignTokenSourceFormat
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class DesignTokenDescriptionExtractorTest {
    @Test
    fun `extracts a trailing block comment`() {
        val description =
            DesignTokenDescriptionExtractor.extract(
                lines =
                    listOf(
                        ":root {",
                        "    --tui-background-overlay-glass-on-light: var(--tui-const-black-alpha-40); /* Оверлей стеклянных поверхностей на светлых фонах, не меняется при смене темы */",
                        "}",
                    ),
                declarationLine = 2,
            )

        assertEquals(
            "Оверлей стеклянных поверхностей на светлых фонах, не меняется при смене темы",
            description,
        )
    }

    @Test
    fun `extracts an adjacent multiline block comment`() {
        val description =
            DesignTokenDescriptionExtractor.extract(
                lines =
                    listOf(
                        "/**",
                        " * Text color for secondary content.",
                        " * Use it for supporting labels.",
                        " */",
                        "--tui-text-secondary: var(--tui-const-black-alpha-54);",
                    ),
                declarationLine = 5,
            )

        assertEquals(
            "Text color for secondary content. Use it for supporting labels.",
            description,
        )
    }

    @Test
    fun `extracts adjacent line comments for less and scss`() {
        val description =
            DesignTokenDescriptionExtractor.extract(
                lines =
                    listOf(
                        "// Text color for secondary content.",
                        "// It is shared by mobile themes.",
                        "--tui-text-secondary: var(--tui-const-cool-gray-lighter-60);",
                    ),
                declarationLine = 3,
            )

        assertEquals(
            "Text color for secondary content. It is shared by mobile themes.",
            description,
        )
    }

    @Test
    fun `does not associate a comment separated by an empty line`() {
        val description =
            DesignTokenDescriptionExtractor.extract(
                lines =
                    listOf(
                        "/* Section colors */",
                        "",
                        "--tui-text-secondary: var(--tui-const-black-alpha-54);",
                    ),
                declarationLine = 3,
            )

        assertNull(description)
    }

    @Test
    fun `ignores tool directives`() {
        val description =
            DesignTokenDescriptionExtractor.extract(
                lines =
                    listOf(
                        "/* stylelint-disable custom-property-pattern */",
                        "--tui-text-secondary: var(--tui-const-black-alpha-54);",
                    ),
                declarationLine = 2,
            )

        assertNull(description)
    }

    @Test
    fun `returns null for invalid declaration line`() {
        assertNull(DesignTokenDescriptionExtractor.extract(listOf("--tui-token: red;"), 0))
        assertNull(DesignTokenDescriptionExtractor.extract(listOf("--tui-token: red;"), 2))
    }

    @Test
    fun `extracts trailing line comment and prefers first comment syntax after semicolon`() {
        assertEquals(
            "Line description",
            DesignTokenDescriptionExtractor.extract(
                listOf("--tui-token: red; // Line description"),
                1,
            ),
        )
        assertEquals(
            "Block description",
            DesignTokenDescriptionExtractor.extract(
                listOf("--tui-token: red; /* Block description */ // later"),
                1,
            ),
        )
    }

    @Test
    fun `extracts multiline trailing block comment`() {
        assertEquals(
            "First line Second line",
            DesignTokenDescriptionExtractor.extract(
                listOf(
                    "--tui-token: red; /* First line",
                    " * Second line",
                    " */",
                ),
                1,
            ),
        )
    }

    @Test
    fun `ignores unterminated trailing block and falls back to leading description`() {
        assertEquals(
            "Leading description",
            DesignTokenDescriptionExtractor.extract(
                listOf(
                    "// Leading description",
                    "--tui-token:",
                    "  red; /* unterminated",
                ),
                2,
            ),
        )
    }

    @Test
    fun `rejects embedded block comments as leading descriptions`() {
        assertNull(
            DesignTokenDescriptionExtractor.extract(
                listOf(
                    "prefix /* Not adjacent metadata */",
                    "--tui-token: red;",
                ),
                2,
            ),
        )
        assertNull(
            DesignTokenDescriptionExtractor.extract(
                listOf(
                    "/* Not adjacent metadata */ suffix",
                    "--tui-token: red;",
                ),
                2,
            ),
        )
    }

    @Test
    fun `ignores every tool directive prefix`() {
        listOf(
            "stylelint-disable rule",
            "prettier-ignore",
            "noinspection CssInvalidPropertyValue",
            "language=CSS",
            "region palette",
            "endregion",
            "todo later",
            "fixme later",
        ).forEach { directive ->
            assertNull(
                DesignTokenDescriptionExtractor.extract(
                    listOf(
                        "/* $directive */",
                        "--tui-token: red;",
                    ),
                    2,
                ),
            )
        }
    }

    @Test
    fun `leading comment scan stops at preceding non comment content`() {
        assertEquals(
            "Description",
            DesignTokenDescriptionExtractor.extract(
                listOf(
                    ".previous { color: red; }",
                    "// Description",
                    "--tui-token: red;",
                ),
                3,
            ),
        )
    }

    @Test
    fun `orphan block terminator is not treated as a leading description`() {
        assertNull(
            DesignTokenDescriptionExtractor.extract(
                listOf(
                    "plain text",
                    "*/",
                    "--tui-token: red;",
                ),
                3,
            ),
        )
    }

    @Test
    fun `does not scan beyond maximum declaration span for trailing comment`() {
        val lines =
            buildList {
                add("--tui-token:")
                repeat(13) { add("    calc(1px + 1px)") }
                add("    red; /* Too far away */")
            }

        assertNull(DesignTokenDescriptionExtractor.extract(lines, 1))
    }

    @Test
    fun `collection extraction requires one distinct useful description`() {
        val first = Files.createTempFile("description-one", ".css")
        val second = Files.createTempFile("description-two", ".css")
        val missing = first.resolveSibling("missing-description.css")

        try {
            Files.writeString(first, "--tui-token: red; /* Same */")
            Files.writeString(second, "--tui-token: blue; /* Same */")

            val same =
                listOf(first, second).map { source ->
                    DesignTokenOrigin(
                        sourceFile = source,
                        line = 1,
                        format = DesignTokenSourceFormat.CSS,
                    )
                }

            assertEquals("Same", DesignTokenDescriptionExtractor.extract(same))

            Files.writeString(second, "--tui-token: blue; /* Different */")
            Files.setLastModifiedTime(second, FileTime.fromMillis(System.currentTimeMillis() + 2_000))
            assertNull(DesignTokenDescriptionExtractor.extract(same))

            val missingOrigin =
                DesignTokenOrigin(
                    sourceFile = missing,
                    line = 1,
                    format = DesignTokenSourceFormat.CSS,
                )
            assertEquals("Same", DesignTokenDescriptionExtractor.extract(listOf(same.first(), missingOrigin)))
        } finally {
            Files.deleteIfExists(first)
            Files.deleteIfExists(second)
        }
    }

    @Test
    fun `refreshes a cached source after the file changes`() {
        val sourceFile = Files.createTempFile("design-token-description", ".css")
        val origin =
            DesignTokenOrigin(
                sourceFile = sourceFile,
                line = 1,
                format = DesignTokenSourceFormat.CSS,
                selectorChain = listOf(":root"),
            )

        try {
            Files.writeString(sourceFile, "--tui-token: #fff; /* First description */")
            assertEquals("First description", DesignTokenDescriptionExtractor.extract(listOf(origin)))

            Files.writeString(sourceFile, "--tui-token: #fff; /* Updated description */")
            Files.setLastModifiedTime(sourceFile, FileTime.fromMillis(System.currentTimeMillis() + 2_000))

            assertEquals("Updated description", DesignTokenDescriptionExtractor.extract(listOf(origin)))
        } finally {
            Files.deleteIfExists(sourceFile)
        }
    }
}
