package org.taigaui.designtokens.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class DesignTokenDeprecationTest {
    @Test
    fun `deprecated marker without details keeps nullable message`() {
        val deprecation =
            DesignTokenDeprecationParser.parse(
                "/** @deprecated */",
                "--tui-old",
            )

        assertNotNull(deprecation)
        assertNull(deprecation?.message)
        assertNull(deprecation?.replacement)
    }

    @Test
    fun `parses explicit replacement from deprecated comment`() {
        val deprecation =
            DesignTokenDeprecationParser.parse(
                """
                /**
                 * @deprecated use --tui-text-primary instead
                 */
                """.trimIndent(),
                "--tui-text-01",
            )

        assertEquals("use --tui-text-primary instead", deprecation?.message)
        assertEquals("--tui-text-primary", deprecation?.replacement)
    }

    @Test
    fun `does not guess replacement when comment mentions several tokens`() {
        val deprecation =
            DesignTokenDeprecationParser.parse(
                "/* @deprecated use --tui-a or --tui-b depending on context */",
                "--tui-old",
            )

        assertNull(deprecation?.replacement)
    }

    @Test
    fun `ignores ordinary comments`() {
        assertNull(
            DesignTokenDeprecationParser.parse(
                "/* Main text color */",
                "--tui-text-primary",
            ),
        )
    }

    @Test
    fun `project override suppresses package deprecation`() {
        val packageRoot = Path.of("node_modules/@taiga-ui/design-tokens")
        val projectRoot = Path.of("app")
        val packageIndex =
            DesignTokenIndex.build(
                packageRoot,
                listOf(
                    declaration(
                        name = "--tui-old",
                        sourceFile = packageRoot.resolve("tokens.css"),
                        packageName = "@taiga-ui/design-tokens",
                        deprecation =
                            DesignTokenDeprecation(
                                message = "use --tui-new instead",
                                replacement = "--tui-new",
                            ),
                    ),
                ),
            )
        val projectIndex =
            DesignTokenIndex.build(
                projectRoot,
                listOf(
                    declaration(
                        name = "--tui-old",
                        sourceFile = projectRoot.resolve("styles.css"),
                        packageName = PROJECT_STYLES_PACKAGE,
                    ),
                ),
            )
        val merged = DesignTokenIndex.merge(listOf(packageIndex, projectIndex))

        assertNull(merged.deprecationFor("--tui-old"))
        assertTrue(merged.find("--tui-old").isNotEmpty())
    }

    @Test
    fun `project deprecation replaces package metadata when explicitly declared`() {
        val projectRoot = Path.of("app")
        val projectIndex =
            DesignTokenIndex.build(
                projectRoot,
                listOf(
                    declaration(
                        name = "--tui-old",
                        sourceFile = projectRoot.resolve("styles.css"),
                        packageName = PROJECT_STYLES_PACKAGE,
                        deprecation =
                            DesignTokenDeprecation(
                                message = "use --tui-local instead",
                                replacement = "--tui-local",
                            ),
                    ),
                ),
            )

        assertEquals("--tui-local", projectIndex.deprecationFor("--tui-old")?.replacement)
    }
}

private fun declaration(
    name: String,
    sourceFile: Path,
    packageName: String,
    deprecation: DesignTokenDeprecation? = null,
): DesignTokenDeclaration =
    DesignTokenDeclaration(
        name = name,
        value = "#fff",
        sourceFile = sourceFile,
        line = 1,
        selectorChain = listOf(":root"),
        packageName = packageName,
        deprecation = deprecation,
    )
