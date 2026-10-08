package org.taigaui.designtokens.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ProjectStylesheetPathResolverCoverageTest {
    @Test
    fun `recognizes supported stylesheet extensions case-insensitively`() {
        assertTrue(
            ProjectStylesheetPathResolver
                .isStylesheet(Path.of("a.css")),
        )
        assertTrue(
            ProjectStylesheetPathResolver
                .isStylesheet(Path.of("a.LESS")),
        )
        assertTrue(
            ProjectStylesheetPathResolver
                .isStylesheet(Path.of("a.scss")),
        )
        assertFalse(
            ProjectStylesheetPathResolver
                .isStylesheet(Path.of("a.ts")),
        )
        assertFalse(
            ProjectStylesheetPathResolver
                .isStylesheet(Path.of("README")),
        )
    }

    @Test
    fun `detects node modules only inside workspace`() {
        val workspace = Path.of("workspace")

        assertTrue(
            ProjectStylesheetPathResolver.isNodeModulesPath(
                workspace.resolve("node_modules/pkg/a.css"),
                workspace,
            ),
        )
        assertFalse(
            ProjectStylesheetPathResolver.isNodeModulesPath(
                workspace.resolve("src/a.css"),
                workspace,
            ),
        )
    }

    @Test
    fun `resolves direct partial extensionless and index stylesheets`() {
        val root = Files.createTempDirectory("taiga-styles")

        try {
            val direct = Files.writeString(root.resolve("direct.css"), ":root {}")
            val partial = Files.writeString(root.resolve("_partial.less"), ":root {}")
            val extensionless = Files.writeString(root.resolve("theme.scss"), ":root {}")
            val indexDirectory = Files.createDirectories(root.resolve("folder"))
            val index = Files.writeString(indexDirectory.resolve("_index.scss"), ":root {}")

            assertEquals(
                direct.toAbsolutePath().normalize(),
                ProjectStylesheetPathResolver.resolveSourceFile(direct),
            )
            assertEquals(
                partial.toAbsolutePath().normalize(),
                ProjectStylesheetPathResolver.resolveSourceFile(root.resolve("partial.less")),
            )
            assertEquals(
                extensionless.toAbsolutePath().normalize(),
                ProjectStylesheetPathResolver.resolveSourceFile(root.resolve("theme")),
            )
            assertEquals(
                index.toAbsolutePath().normalize(),
                ProjectStylesheetPathResolver.resolveSourceFile(indexDirectory),
            )
            assertNull(ProjectStylesheetPathResolver.resolveSourceFile(root.resolve("missing")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `classifies external and package imports`() {
        listOf(
            "http://example.com/a.css",
            "HTTP://example.com/a.css",
            "https://example.com/a.css",
            "data:text/css,body{}",
            "sass:color",
        ).forEach { value ->
            assertTrue(
                "Expected external: $value",
                ProjectStylesheetPathResolver.isExternalImport(value),
            )
        }

        assertFalse(ProjectStylesheetPathResolver.isExternalImport("./local.scss"))
        assertTrue(ProjectStylesheetPathResolver.isPackageImport("@taiga-ui/core/styles/taiga-ui-local.less"))
        assertTrue(ProjectStylesheetPathResolver.isPackageImport("node_modules/pkg/styles.css"))
        assertFalse(ProjectStylesheetPathResolver.isPackageImport("./node_modules/pkg/styles.css"))
    }
}
