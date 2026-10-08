package org.taigaui.designtokens.packageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class YarnPnpPackageMaterializerTest {
    @Test
    fun `materializes physical package and tracks its manifest version`() {
        val root = Files.createTempDirectory("pnp-materializer-physical")
        val packageRoot = root.resolve("node_modules/@taiga-ui/core")
        val cacheRoot = root.resolve("cache")

        try {
            Files.createDirectories(packageRoot)
            val manifest = Files.writeString(packageRoot.resolve("package.json"), """{"name":"@taiga-ui/core"}""")
            Files.setLastModifiedTime(manifest, FileTime.fromMillis(12_345L))

            val result =
                requireNotNull(
                    YarnPnpPackageMaterializer(cacheRoot)
                        .materialize(
                            manifestRoot = root,
                            packageLocation = "./node_modules/@taiga-ui/core",
                            identity = "core",
                        ),
                )

            assertEquals(packageRoot.toRealPath(), result.root)
            assertEquals(
                Files.size(manifest).toString() + ":" + Files.getLastModifiedTime(manifest).toMillis(),
                result.contentVersion,
            )
            assertEquals(setOf(packageRoot.toRealPath()), result.invalidationRoots)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `physical package falls back to root timestamp when manifest is missing`() {
        val root = Files.createTempDirectory("pnp-materializer-fallback")
        val packageRoot = Files.createDirectories(root.resolve("package"))

        try {
            val result =
                requireNotNull(
                    YarnPnpPackageMaterializer(root.resolve("cache"))
                        .materialize(
                            manifestRoot = root,
                            packageLocation = "./package",
                            identity = "package",
                        ),
                )

            assertEquals(
                Files.getLastModifiedTime(packageRoot).toMillis().toString(),
                result.contentVersion,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `returns null for missing physical package`() {
        val root = Files.createTempDirectory("pnp-materializer-missing")

        try {
            assertNull(
                YarnPnpPackageMaterializer(root.resolve("cache"))
                    .materialize(
                        manifestRoot = root,
                        packageLocation = "./missing",
                        identity = "missing",
                    ),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `returns null when archive path does not exist`() {
        val root = Files.createTempDirectory("pnp-materializer-missing-archive")

        try {
            assertNull(
                YarnPnpPackageMaterializer(root.resolve("cache"))
                    .materialize(
                        manifestRoot = root,
                        packageLocation = "./.yarn/cache/missing.zip/node_modules/pkg",
                        identity = "missing-archive",
                    ),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `extracts archive package once and reuses completed cache`() {
        val root = Files.createTempDirectory("pnp-materializer-archive")
        val archive = root.resolve(".yarn/cache/core.zip")
        val cacheRoot = root.resolve("cache")

        try {
            createZip(
                archive,
                linkedMapOf(
                    "outside.txt" to "ignored",
                    "node_modules/@taiga-ui/core/" to null,
                    "node_modules/@taiga-ui/core/styles/" to null,
                    "node_modules/@taiga-ui/core/package.json" to """{"name":"@taiga-ui/core"}""",
                    "node_modules/@taiga-ui/core/styles/token.css" to ":root{}",
                ),
            )
            val materializer = YarnPnpPackageMaterializer(cacheRoot)
            val first =
                requireNotNull(
                    materializer.materialize(
                        manifestRoot = root,
                        packageLocation = "./.yarn/cache/core.zip/node_modules/@taiga-ui/core",
                        identity = "core",
                    ),
                )
            val marker = first.root.resolve(".complete")
            val firstMarkerTime = Files.getLastModifiedTime(marker)

            val second =
                requireNotNull(
                    materializer.materialize(
                        manifestRoot = root,
                        packageLocation = "./.yarn/cache/core.zip/node_modules/@taiga-ui/core",
                        identity = "core",
                    ),
                )

            assertEquals(first.root, second.root)
            assertTrue(Files.isRegularFile(first.root.resolve("package.json")))
            assertTrue(Files.isRegularFile(first.root.resolve("styles/token.css")))
            assertFalse(Files.exists(first.root.resolve("outside.txt")))
            assertEquals(firstMarkerTime, Files.getLastModifiedTime(marker))
            assertEquals(setOf(archive.toRealPath()), first.invalidationRoots)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects invalid archive missing manifest and path traversal`() {
        val root = Files.createTempDirectory("pnp-materializer-invalid")

        try {
            Files.writeString(root.resolve("invalid.zip"), "not-a-zip")
            assertNull(
                YarnPnpPackageMaterializer(root.resolve("cache-invalid"))
                    .materialize(
                        manifestRoot = root,
                        packageLocation = "./invalid.zip/node_modules/pkg",
                        identity = "invalid",
                    ),
            )

            val missingManifest = root.resolve("missing-manifest.zip")
            createZip(
                missingManifest,
                linkedMapOf(
                    "node_modules/pkg/index.css" to ":root{}",
                ),
            )
            assertNull(
                YarnPnpPackageMaterializer(root.resolve("cache-missing"))
                    .materialize(
                        manifestRoot = root,
                        packageLocation = "./missing-manifest.zip/node_modules/pkg",
                        identity = "missing",
                    ),
            )

            val traversal = root.resolve("traversal.zip")
            createZip(
                traversal,
                linkedMapOf(
                    "node_modules/pkg/../../escape.txt" to "escape",
                    "node_modules/pkg/package.json" to "{}",
                ),
            )
            assertNull(
                YarnPnpPackageMaterializer(root.resolve("cache-traversal"))
                    .materialize(
                        manifestRoot = root,
                        packageLocation = "./traversal.zip/node_modules/pkg",
                        identity = "traversal",
                    ),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `enforces archive file and byte limits with small test limits`() {
        val root = Files.createTempDirectory("pnp-materializer-limits")

        try {
            val filesArchive = root.resolve("files.zip")
            createZip(
                filesArchive,
                linkedMapOf(
                    "node_modules/pkg/package.json" to "{}",
                    "node_modules/pkg/extra.css" to "x",
                ),
            )
            assertNull(
                YarnPnpPackageMaterializer(
                    cacheRoot = root.resolve("cache-files"),
                    maxExtractedFiles = 1,
                ).materialize(
                    manifestRoot = root,
                    packageLocation = "./files.zip/node_modules/pkg",
                    identity = "files",
                ),
            )

            val bytesArchive = root.resolve("bytes.zip")
            createZip(
                bytesArchive,
                linkedMapOf(
                    "node_modules/pkg/package.json" to "{}",
                    "node_modules/pkg/extra.css" to "x",
                ),
            )
            assertNull(
                YarnPnpPackageMaterializer(
                    cacheRoot = root.resolve("cache-bytes"),
                    maxExtractedBytes = 2,
                ).materialize(
                    manifestRoot = root,
                    packageLocation = "./bytes.zip/node_modules/pkg",
                    identity = "bytes",
                ),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `hash and path fallbacks are deterministic`() {
        val first = stablePnpHash("same")
        val second = stablePnpHash("same")

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertNotEquals(first, stablePnpHash("different"))

        val missing = Path.of("build/definitely-missing-pnp-path")
        val resolved = missing.toRealPathOrSelf()

        assertTrue(resolved.isAbsolute)
        assertEquals(missing.toAbsolutePath().normalize(), resolved)
    }

    @Test
    fun `archive splitter accepts relative archive path`() {
        val materializer = YarnPnpPackageMaterializer(Path.of("build/materializer-cache"))
        val method =
            YarnPnpPackageMaterializer::class.java
                .getDeclaredMethod("splitArchiveLocation", Path::class.java)
                .apply { isAccessible = true }

        assertNotNull(
            method.invoke(
                materializer,
                Path.of("cache/pkg.zip/node_modules/pkg"),
            ),
        )
    }

    private fun createZip(
        archive: Path,
        entries: Map<String, String?>,
    ) {
        archive.parent?.let(Files::createDirectories)

        ZipOutputStream(Files.newOutputStream(archive)).use { output ->
            entries.forEach { (name, value) ->
                output.putNextEntry(ZipEntry(name))

                if (value != null) {
                    output.write(value.toByteArray())
                }

                output.closeEntry()
            }
        }
    }
}
