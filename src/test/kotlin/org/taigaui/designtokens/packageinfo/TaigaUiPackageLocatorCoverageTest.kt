package org.taigaui.designtokens.packageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class TaigaUiPackageLocatorCoverageTest {
    @Test
    fun `discovers valid physical packages and ignores incomplete scope entries`() {
        val workspace = Files.createTempDirectory("taiga-locator-node-modules")

        try {
            val scopeRoot = workspace.resolve("node_modules/@taiga-ui")
            val core = scopeRoot.resolve("core")
            val wrong = scopeRoot.resolve("wrong")
            val incomplete = scopeRoot.resolve("incomplete")
            val source = workspace.resolve("src/app.ts")

            write(
                core.resolve("package.json"),
                """{"name":"@taiga-ui/core","version":"5.1.0"}""",
            )
            write(
                wrong.resolve("package.json"),
                """{"name":"third-party","version":"1.0.0"}""",
            )
            Files.createDirectories(incomplete)
            write(source, "export const value = 1;")

            val located = requireNotNull(TaigaUiPackageLocator().locate(source))
            val corePackage = requireNotNull(located.packages["@taiga-ui/core"])

            assertEquals(
                workspace.toAbsolutePath().normalize(),
                located.workspaceRoot,
            )
            assertEquals(
                scopeRoot.toAbsolutePath().normalize(),
                located.discoveryRoot,
            )
            assertEquals(setOf("@taiga-ui/core"), located.packages.keys)
            assertTrue(located.identity.startsWith("node-modules:"))
            assertEquals("@taiga-ui/core@5.1.0", located.contentVersion)
            assertTrue(corePackage.identity.startsWith("fs:"))
            assertEquals("5.1.0", corePackage.contentVersion)
            assertEquals(located.discoveryRoot, located.cacheKey)

            assertEquals(
                located.discoveryRoot,
                requireNotNull(TaigaUiPackageLocator().locate(source.parent)).discoveryRoot,
            )
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `returns physical scope even when every package entry is rejected`() {
        val workspace = Files.createTempDirectory("taiga-locator-empty-scope")

        try {
            val scopeRoot = workspace.resolve("node_modules/@taiga-ui")
            val source = workspace.resolve("src/app.ts")

            write(
                scopeRoot.resolve("wrong/package.json"),
                """{"name":"not-taiga","version":"1.0.0"}""",
            )
            write(source, "export const value = 1;")

            val located = requireNotNull(TaigaUiPackageLocator().locate(source))

            assertTrue(located.packages.isEmpty())
            assertEquals("", located.contentVersion)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `returns null when neither node modules nor pnp metadata exists`() {
        val workspace = Files.createTempDirectory("taiga-locator-missing")

        try {
            val source = workspace.resolve("src/app.ts")

            write(source, "export const value = 1;")

            assertNull(TaigaUiPackageLocator().locate(source))
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `discovers reachable taiga package through yarn pnp`() {
        val workspace = Files.createTempDirectory("taiga-locator-pnp")

        try {
            val source = workspace.resolve("src/app.ts")
            val core = workspace.resolve("packages/core")
            write(source, "export const value = 1;")
            write(
                core.resolve("package.json"),
                """{"name":"@taiga-ui/core","version":"5.2.0"}""",
            )
            writePnp(
                workspace,
                topLocation = "./",
                dependencies = """[["@taiga-ui/core","npm:5.2.0"]]""",
                coreLocation = "./packages/core/",
            )

            val located = requireNotNull(TaigaUiPackageLocator().locate(source))

            assertEquals(workspace.toAbsolutePath().normalize(), located.workspaceRoot)
            assertEquals(setOf("@taiga-ui/core"), located.packages.keys)
            assertTrue(located.identity.startsWith("yarn-pnp:"))
            assertTrue(located.contentVersion.contains("@taiga-ui/core@5.2.0"))
            assertTrue(located.invalidationRoots.isNotEmpty())
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `yarn pnp returns null when no taiga packages are reachable`() {
        val workspace = Files.createTempDirectory("taiga-locator-pnp-empty")

        try {
            val source = workspace.resolve("src/app.ts")
            write(source, "export const value = 1;")
            writePnp(
                workspace,
                topLocation = "./",
                dependencies = "[]",
            )

            assertNull(TaigaUiPackageLocator().locate(source))
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `yarn pnp rejects unreachable materialization missing metadata and wrong package name`() {
        fun locateWithPackage(
            suffix: String,
            prepare: (Path) -> Unit,
        ): TaigaUiPackageScope? {
            val workspace = Files.createTempDirectory("taiga-locator-pnp-$suffix")
            val source = workspace.resolve("src/app.ts")
            val core = workspace.resolve("packages/core")

            write(source, "export const value = 1;")
            prepare(core)
            writePnp(
                workspace,
                topLocation = "./",
                dependencies = """[["@taiga-ui/core","npm:5.2.0"]]""",
                coreLocation = "./packages/core/",
            )

            return try {
                TaigaUiPackageLocator().locate(source)
            } finally {
                workspace.toFile().deleteRecursively()
            }
        }

        assertNull(locateWithPackage("missing") { })
        assertNull(locateWithPackage("no-metadata") { root -> Files.createDirectories(root) })
        assertNull(
            locateWithPackage("wrong-name") { root ->
                write(
                    root.resolve("package.json"),
                    """{"name":"third-party","version":"1.0.0"}""",
                )
            },
        )
    }

    @Test
    fun `yarn pnp falls back to manifest root when issuer has no physical path`() {
        val workspace = Files.createTempDirectory("taiga-locator-pnp-root-fallback")

        try {
            val source = workspace.resolve("src/app.ts")
            val core = workspace.resolve("packages/core")
            write(source, "export const value = 1;")
            write(
                core.resolve("package.json"),
                """{"name":"@taiga-ui/core","version":"5.2.0"}""",
            )
            writePnp(
                workspace,
                topLocation = "./.yarn/cache/app.zip/node_modules/app/",
                dependencies = """[["@taiga-ui/core","npm:5.2.0"]]""",
                coreLocation = "./packages/core/",
                enableTopLevelFallback = true,
            )

            val located = requireNotNull(TaigaUiPackageLocator().locate(source))

            assertEquals(workspace.toAbsolutePath().normalize(), located.workspaceRoot)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun writePnp(
        workspace: Path,
        topLocation: String,
        dependencies: String,
        coreLocation: String? = null,
        enableTopLevelFallback: Boolean = false,
    ) {
        val coreRegistry =
            coreLocation
                ?.let { location ->
                    """,
                    [
                      "@taiga-ui/core",
                      [["npm:5.2.0", {"packageLocation":"$location","packageDependencies":[]}]]
                    ]"""
                }.orEmpty()

        write(
            workspace.resolve(".pnp.data.json"),
            """
            {
              "enableTopLevelFallback": $enableTopLevelFallback,
              "packageRegistryData": [
                [
                  null,
                  [[null, {"packageLocation":"$topLocation","packageDependencies":$dependencies}]]
                ]
                $coreRegistry
              ]
            }
            """.trimIndent(),
        )
    }

    private fun write(
        path: Path,
        content: String,
    ) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }
}
