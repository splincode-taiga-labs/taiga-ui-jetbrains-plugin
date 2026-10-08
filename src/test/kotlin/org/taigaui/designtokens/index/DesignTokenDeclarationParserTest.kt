package org.taigaui.designtokens.index

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class DesignTokenDeclarationParserTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val extractor: DesignTokenSourceExtractor = DesignTokenDeclarationParser()

    @Test
    fun `parses declarations with values and source locations`() {
        val sourceFile =
            createSourceFile(
                "tokens.scss",
                """
                :root {
                    --tui-text-primary: #000;
                    --tui-shadow:
                        0 1px 2px rgb(0 0 0 / 10%);
                    --tui-data: url("data:image/svg+xml;utf8,<svg></svg>");
                    --tui-last: white
                }
                """.trimIndent(),
            )
        val normalizedSourceFile = sourceFile.toAbsolutePath().normalize()

        val result = extractor.extract(sourceFile)

        assertEquals(
            listOf(
                DesignTokenDeclaration(
                    name = "--tui-text-primary",
                    value = "#000",
                    sourceFile = normalizedSourceFile,
                    line = 2,
                ),
                DesignTokenDeclaration(
                    name = "--tui-shadow",
                    value = "0 1px 2px rgb(0 0 0 / 10%)",
                    sourceFile = normalizedSourceFile,
                    line = 3,
                ),
                DesignTokenDeclaration(
                    name = "--tui-data",
                    value = "url(\"data:image/svg+xml;utf8,<svg></svg>\")",
                    sourceFile = normalizedSourceFile,
                    line = 5,
                ),
                DesignTokenDeclaration(
                    name = "--tui-last",
                    value = "white",
                    sourceFile = normalizedSourceFile,
                    line = 6,
                ),
            ),
            result,
        )
    }

    @Test
    fun `ignores references comments strings and unrelated properties`() {
        val sourceFile =
            createSourceFile(
                "ignored.less",
                """
                :root {
                    color: var(--tui-text-primary);
                    --company-token: red;
                    /* --tui-block-commented: red; */
                    // --tui-line-commented: blue;
                    content: "--tui-string-token: green;";
                }
                """.trimIndent(),
            )

        assertEquals(emptyList<DesignTokenDeclaration>(), extractor.extract(sourceFile))
    }

    @Test
    fun `supports whitespace and comments before the colon`() {
        val sourceFile =
            createSourceFile(
                "spacing.css",
                "--tui-text-warning /* generated */ : rgb(255 100 0);",
            )

        val result = extractor.extract(sourceFile)

        assertEquals(1, result.size)
        assertEquals("--tui-text-warning", result.single().name)
        assertEquals("rgb(255 100 0)", result.single().value)
        assertEquals(1, result.single().line)
    }

    @Test
    fun `keeps semicolons braces and comment markers inside nested values`() {
        val sourceFile =
            createSourceFile(
                "nested.less",
                """
                :root {
                    --tui-array: [one; two; three];
                    --tui-function: fn(one; two, nested(three; four));
                    --tui-url: url("https://example.com/a;b");
                    --tui-escaped: "quote \" ; still string";
                }
                """.trimIndent(),
            )

        val result = extractor.extract(sourceFile).associateBy(DesignTokenDeclaration::name)

        assertEquals("[one; two; three]", result.getValue("--tui-array").value)
        assertEquals(
            "fn(one; two, nested(three; four))",
            result.getValue("--tui-function").value,
        )
        assertEquals(
            """url("https://example.com/a;b")""",
            result.getValue("--tui-url").value,
        )
        assertEquals(
            """"quote \" ; still string"""",
            result.getValue("--tui-escaped").value,
        )
    }

    @Test
    fun `handles comments strings and line comments that reach end of file`() {
        val sourceFile =
            createSourceFile(
                "unterminated.less",
                """
                :root {
                    --tui-before: red;
                    content: "unterminated --tui-fake: blue;
                    /* --tui-block-fake: green
                    // --tui-line-fake: pink;
                }
                """.trimIndent(),
            )

        val result = extractor.extract(sourceFile)

        assertEquals(listOf("--tui-before"), result.map(DesignTokenDeclaration::name))
    }

    @Test
    fun `ignores empty declaration values`() {
        val sourceFile =
            createSourceFile(
                "empty.css",
                """
                :root {
                    --tui-empty: ;
                    --tui-empty-before-brace: }
                """.trimIndent(),
            )

        assertEquals(emptyList<DesignTokenDeclaration>(), extractor.extract(sourceFile))
    }

    @Test
    fun `tracks declarations beginning exactly at a new line`() {
        val sourceFile =
            createSourceFile(
                "lines.css",
                "--tui-first: red;\n--tui-second: blue;",
            )

        val result = extractor.extract(sourceFile)

        assertEquals(listOf(1, 2), result.map(DesignTokenDeclaration::line))
    }

    @Test
    fun `returns empty list when source file cannot be read`() {
        val missingFile = temporaryFolder.root.toPath().resolve("missing.css")

        assertEquals(emptyList<DesignTokenDeclaration>(), extractor.extract(missingFile))
    }

    private fun createSourceFile(
        name: String,
        content: String,
    ): Path {
        val sourceFile = temporaryFolder.root.toPath().resolve(name)
        Files.writeString(sourceFile, content)

        return sourceFile
    }
}
