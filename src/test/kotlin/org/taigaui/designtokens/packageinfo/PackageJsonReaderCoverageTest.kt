package org.taigaui.designtokens.packageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PackageJsonReaderCoverageTest {
    @Test
    fun `reads metadata and export capabilities`() {
        val root = Files.createTempDirectory("package-json")

        try {
            val packageJson =
                Files.writeString(
                    root.resolve("package.json"),
                    """
                    {
                        "name": "@taiga-ui/styles",
                        "version": "5.0.0",
                        "exports": {
                            "./styles/*": "./styles/*",
                            "./*": "./*"
                        }
                    }
                    """.trimIndent(),
                )

            val metadata = requireNotNull(PackageJsonReader().readMetadata(packageJson))

            assertEquals("@taiga-ui/styles", metadata.name)
            assertEquals("5.0.0", metadata.version)
            assertTrue(metadata.exportsStyles)
            assertTrue(metadata.exportsPackageRoot)
            assertEquals("5.0.0", PackageJsonReader().readVersion(packageJson))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `returns null for missing unreadable or incomplete package metadata`() {
        val root = Files.createTempDirectory("package-json")

        try {
            val missing = root.resolve("missing.json")
            val noName = Files.writeString(root.resolve("no-name.json"), """{"version":"1.0.0"}""")
            val noVersion = Files.writeString(root.resolve("no-version.json"), """{"name":"pkg"}""")
            val blankName =
                Files.writeString(
                    root.resolve("blank-name.json"),
                    """{"name":"   ","version":"1.0.0"}""",
                )

            assertNull(PackageJsonReader().readMetadata(missing))
            assertNull(PackageJsonReader().readMetadata(noName))
            assertNull(PackageJsonReader().readMetadata(noVersion))
            assertNull(PackageJsonReader().readMetadata(blankName))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `does not infer exports when export keys are absent`() {
        val root = Files.createTempDirectory("package-json")

        try {
            val packageJson =
                Files.writeString(
                    root.resolve("package.json"),
                    """{"name":"@taiga-ui/core","version":"5.0.0","exports":{".":"./index.js"}}""",
                )

            val metadata = requireNotNull(PackageJsonReader().readMetadata(packageJson))

            assertFalse(metadata.exportsStyles)
            assertFalse(metadata.exportsPackageRoot)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
