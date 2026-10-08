package org.taigaui.designtokens.packageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PackageJsonReaderTest {
    @Test
    fun `reads version metadata and supported export shapes`() {
        val file = Files.createTempFile("package", ".json")

        Files.writeString(
            file,
            """
            {
                "name": "  @taiga-ui/core  ",
                "version": "  5.0.0  ",
                "exports": {
                    "./styles/*": "./styles/*",
                    "./*": "./*"
                }
            }
            """.trimIndent(),
        )

        val reader = PackageJsonReader()
        val metadata = requireNotNull(reader.readMetadata(file))

        assertEquals("@taiga-ui/core", metadata.name)
        assertEquals("5.0.0", reader.readVersion(file))
        assertTrue(metadata.exportsStyles)
        assertTrue(metadata.exportsPackageRoot)
    }

    @Test
    fun `recognizes exact styles export and missing package root export`() {
        val file = Files.createTempFile("package-styles", ".json")

        Files.writeString(
            file,
            """{"name":"pkg","version":"1","exports":{"./styles":"./styles.css"}}""",
        )

        val metadata = requireNotNull(PackageJsonReader().readMetadata(file))

        assertTrue(metadata.exportsStyles)
        assertFalse(metadata.exportsPackageRoot)
    }

    @Test
    fun `returns null for missing unreadable and incomplete metadata`() {
        val root = Files.createTempDirectory("package-json-reader")
        val reader = PackageJsonReader()

        assertNull(reader.readMetadata(root.resolve("missing.json")))

        val directory = root.resolve("directory.json")
        Files.createDirectories(directory)
        assertNull(reader.readMetadata(directory))

        val missingName = root.resolve("missing-name.json")
        Files.writeString(missingName, """{"version":"1.0.0"}""")
        assertNull(reader.readMetadata(missingName))

        val missingVersion = root.resolve("missing-version.json")
        Files.writeString(missingVersion, """{"name":"pkg"}""")
        assertNull(reader.readMetadata(missingVersion))

        val blankName = root.resolve("blank-name.json")
        Files.writeString(blankName, """{"name":"   ","version":"1.0.0"}""")
        assertNull(reader.readMetadata(blankName))
    }
}
