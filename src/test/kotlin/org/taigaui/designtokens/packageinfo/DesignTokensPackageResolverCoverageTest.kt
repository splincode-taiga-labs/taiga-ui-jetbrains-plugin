package org.taigaui.designtokens.packageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DesignTokensPackageResolverCoverageTest {
    @Test
    fun `discovers design tokens styles and core source roots with stable precedence`() {
        val workspace = Files.createTempDirectory("taiga-packages")

        try {
            val scope = workspace.resolve("node_modules/@taiga-ui")
            val designTokens =
                createPackage(
                    scope,
                    "@taiga-ui/design-tokens",
                    "5.2.0",
                    exports = """"./token": "./token.js"""",
                )
            createPackage(
                scope,
                "@taiga-ui/styles",
                "5.1.0",
                exports = """"./*": "./*"""",
            )
            val core =
                createPackage(
                    scope,
                    "@taiga-ui/core",
                    "5.3.0",
                    exports = """"./styles/*": "./styles/*"""",
                )
            Files.createDirectories(core.resolve("styles"))
            createPackage(
                scope,
                "not-taiga",
                "1.0.0",
                exports = """"./*": "./*"""",
            )

            val resolved = requireNotNull(DesignTokensPackageResolver().resolve(workspace.resolve("src/app.ts")))

            assertEquals(designTokens.toAbsolutePath().normalize(), resolved.realRoot)
            assertEquals("5.2.0", resolved.version)
            assertEquals(
                listOf("@taiga-ui/core", "@taiga-ui/design-tokens", "@taiga-ui/styles"),
                resolved.sourcePackages.map(DesignTokenSourcePackage::name).sorted(),
            )
            assertTrue(resolved.cacheVersion.contains("@taiga-ui/core@5.3.0"))
            assertTrue(resolved.cacheIdentity.isNotBlank())
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `ignores styles export when styles directory is missing and returns null without usable packages`() {
        val workspace = Files.createTempDirectory("taiga-packages")

        try {
            val scope = workspace.resolve("node_modules/@taiga-ui")
            createPackage(
                scope,
                "@taiga-ui/core",
                "5.0.0",
                exports = """"./styles/*": "./styles/*"""",
            )

            assertNull(DesignTokensPackageResolver().resolve(workspace.resolve("src")))
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scope cache key uses discovery directory when present and stable pnp path otherwise`() {
        val directory = Files.createTempDirectory("taiga-scope")

        try {
            val physical =
                TaigaUiPackageScope(
                    workspaceRoot = directory,
                    discoveryRoot = directory,
                    packages = emptyMap(),
                    identity = "physical",
                    contentVersion = "1",
                )
            val virtual =
                TaigaUiPackageScope(
                    workspaceRoot = directory,
                    discoveryRoot = directory.resolve(".pnp.cjs"),
                    packages = emptyMap(),
                    identity = "virtual",
                    contentVersion = "2",
                )

            assertEquals(directory, physical.cacheKey)
            assertTrue(virtual.cacheKey.toString().contains("yarn-pnp-scopes"))
            assertTrue(virtual.cacheKey.isAbsolute)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `source package comparator uses version and path tie breakers`() {
        @Suppress("UNCHECKED_CAST")
        val comparator =
            DesignTokensPackageResolver::class.java
                .getDeclaredField("SOURCE_PACKAGE_COMPARATOR")
                .apply { isAccessible = true }
                .get(null) as Comparator<DesignTokenSourcePackage>
        val root = Path.of("build/comparator").toAbsolutePath().normalize()
        val first =
            DesignTokenSourcePackage(
                name = "@taiga-ui/core",
                root = root.resolve("a"),
                realRoot = root.resolve("a"),
                version = "1.0.0",
                sourceRoots = listOf(root.resolve("a/styles")),
            )
        val newer = first.copy(version = "2.0.0")
        val otherRoot =
            first.copy(
                root = root.resolve("b"),
                realRoot = root.resolve("b"),
                sourceRoots = listOf(root.resolve("b/styles")),
            )

        assertTrue(comparator.compare(first, newer) < 0)
        assertTrue(comparator.compare(first, otherRoot) < 0)
    }

    private fun createPackage(
        scope: Path,
        name: String,
        version: String,
        exports: String,
    ): Path {
        val root = scope.resolve(name.substringAfterLast('/'))
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("package.json"),
            """
            {
                "name": "$name",
                "version": "$version",
                "exports": {
                    $exports
                }
            }
            """.trimIndent(),
        )

        return root
    }
}
