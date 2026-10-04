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

    private fun write(
        path: Path,
        content: String,
    ) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }
}
