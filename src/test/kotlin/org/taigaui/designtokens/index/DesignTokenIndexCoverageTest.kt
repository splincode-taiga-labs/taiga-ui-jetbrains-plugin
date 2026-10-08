package org.taigaui.designtokens.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class DesignTokenIndexCoverageTest {
    private val root = Path.of("build/fixtures/index-coverage").toAbsolutePath().normalize()

    @Test
    fun `orders package origins across all precedence buckets`() {
        val packages =
            listOf(
                "@taiga-ui/design-tokens",
                "@taiga-ui/styles",
                "@taiga-ui/core",
                "@taiga-ui/proprietary",
                PROJECT_STYLES_PACKAGE,
                null,
                "@custom/package",
            )
        val declarations =
            packages.mapIndexed { index, packageName ->
                declaration(
                    file = "same.css",
                    line = index + 1,
                    packageName = packageName,
                )
            }
        val origins =
            DesignTokenIndex
                .build(root, declarations)
                .find(TOKEN)
                .flatMap(DesignTokenVariant::origins)

        assertEquals(packages, origins.map(DesignTokenOrigin::packageName))
    }

    @Test
    fun `origin ordering uses selector chain after identical source and line`() {
        val declarations =
            listOf(
                declaration(
                    file = "same.css",
                    line = 1,
                    selectors = listOf(":root", ".b"),
                ),
                declaration(
                    file = "same.css",
                    line = 1,
                    selectors = listOf(":root", ".a"),
                ),
            )

        val origins =
            DesignTokenIndex
                .build(root, declarations)
                .find(TOKEN)
                .single()
                .origins

        assertEquals(
            listOf(listOf(":root", ".a"), listOf(":root", ".b")),
            origins.map(DesignTokenOrigin::selectorChain),
        )
    }

    @Test
    fun `desktop local override is shared across platforms while mobile is not`() {
        val desktop =
            DesignTokenIndex
                .build(
                    root,
                    listOf(
                        declaration(
                            file = "desktop/token.css",
                            line = 1,
                            localOverride = true,
                        ),
                    ),
                ).find(TOKEN)
                .single()
                .origins
                .single()
        val mobile =
            DesignTokenIndex
                .build(
                    root,
                    listOf(
                        declaration(
                            file = "mobile/token.css",
                            line = 1,
                            localOverride = true,
                        ),
                    ),
                ).find(TOKEN)
                .single()
                .origins
                .single()

        assertTrue(desktop.sharedAcrossPlatforms)
        assertFalse(mobile.sharedAcrossPlatforms)
    }

    @Test
    fun `merge orders equivalent variants by source path and line`() {
        val indexes =
            listOf(
                DesignTokenIndex.build(
                    root,
                    listOf(declaration(file = "b.css", line = 2, packageName = "@taiga-ui/core")),
                ),
                DesignTokenIndex.build(
                    root,
                    listOf(declaration(file = "a.css", line = 3, packageName = "@taiga-ui/core")),
                ),
                DesignTokenIndex.build(
                    root,
                    listOf(declaration(file = "a.css", line = 1, packageName = "@taiga-ui/core")),
                ),
            )
        val variants = DesignTokenIndex.merge(indexes).find(TOKEN)

        assertEquals(
            listOf("a.css:1", "a.css:3", "b.css:2"),
            variants.map { variant ->
                val origin = variant.origins.single()

                "${origin.sourceFile.fileName}:${origin.line}"
            },
        )
    }

    @Test
    fun `deprecation prefers project origins and requires one distinct value`() {
        val packageDeprecation = DesignTokenDeprecation(message = "Package")
        val projectDeprecation = DesignTokenDeprecation(message = "Project")
        val projectIndex =
            DesignTokenIndex.build(
                root,
                listOf(
                    declaration(
                        file = "package.css",
                        line = 1,
                        packageName = "@taiga-ui/core",
                        deprecation = packageDeprecation,
                    ),
                    declaration(
                        file = "project.css",
                        line = 2,
                        packageName = PROJECT_STYLES_PACKAGE,
                        deprecation = projectDeprecation,
                    ),
                ),
            )

        assertEquals(projectDeprecation, projectIndex.deprecationFor(TOKEN))
        assertNull(projectIndex.deprecationFor("--tui-missing"))

        val ambiguous =
            DesignTokenIndex.build(
                root,
                listOf(
                    declaration(
                        file = "a.css",
                        line = 1,
                        packageName = "@taiga-ui/core",
                        deprecation = DesignTokenDeprecation(message = "A"),
                    ),
                    declaration(
                        file = "b.css",
                        line = 2,
                        packageName = "@taiga-ui/core",
                        deprecation = DesignTokenDeprecation(message = "B"),
                    ),
                ),
            )

        assertNull(ambiguous.deprecationFor(TOKEN))
    }

    private fun declaration(
        file: String,
        line: Int,
        packageName: String? = null,
        selectors: List<String> = emptyList(),
        localOverride: Boolean = false,
        deprecation: DesignTokenDeprecation? = null,
    ): DesignTokenDeclaration =
        DesignTokenDeclaration(
            name = TOKEN,
            value = "red",
            sourceFile = root.resolve(file),
            line = line,
            selectorChain = selectors,
            packageName = packageName,
            localOverride = localOverride,
            deprecation = deprecation,
        )

    private companion object {
        const val TOKEN = "--tui-token"
    }
}
