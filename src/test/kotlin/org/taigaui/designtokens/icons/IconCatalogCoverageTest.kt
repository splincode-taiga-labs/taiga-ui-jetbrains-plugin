package org.taigaui.designtokens.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.taigaui.designtokens.packageinfo.LocatedTaigaUiPackage
import org.taigaui.designtokens.packageinfo.TaigaUiPackageScope
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class IconCatalogCoverageTest {
    @Test
    fun `loader returns a local empty catalog without eligible sources`() {
        val root = Files.createTempDirectory("icon-catalog-no-sources")

        try {
            val result = IconCatalogLoader(emptyList()).loadCatalogWithPolicy(root)

            assertTrue(result.catalog.names.isEmpty())
            assertEquals(IconCatalogCachePolicy.LOCAL, result.cachePolicy)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `catalog sorts names deduplicates by last entry and resolves source`() {
        val first = IconSvgSource.Remote(URI("https://example.test/first.svg"))
        val replacement = IconSvgSource.Remote(URI("https://example.test/replacement.svg"))
        val catalog =
            IconCatalog(
                listOf(
                    IconCatalogEntry("@tui.z", first),
                    IconCatalogEntry("@tui.a", first),
                    IconCatalogEntry("@tui.z", replacement),
                ),
            )

        assertEquals(listOf("@tui.a", "@tui.z"), catalog.names)
        assertSame(replacement, catalog.svgSource("@tui.z"))
        assertNull(catalog.svgSource("@tui.missing"))
    }

    @Test
    fun `parser returns empty for missing malformed and invalid icon groups`() {
        assertEquals(emptyList<String>(), TbankIconCatalogParser.parse("{}"))
        assertEquals(
            emptyList<IconCatalogEntry>(),
            TbankIconCatalogParser.parseEntries(
                """
                {
                  "icons": {
                    "": ["clock"],
                    "valid//broken": ["clock"],
                    "valid": ["", "bad name"]
                  }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `parser normalizes groups deduplicates names and builds remote source urls`() {
        val entries =
            TbankIconCatalogParser.parseEntries(
                """
                {
                  "icons": {
                    " fancy / small ": [" clock-circle ", "clock-circle", "arrow_left"],
                    "flags": ["ab"]
                  }
                }
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                "@tui.fancy.small.arrow_left",
                "@tui.fancy.small.clock-circle",
                "@tui.flags.ab",
            ),
            entries.map(IconCatalogEntry::name),
        )
        assertTrue(
            entries.all { entry ->
                entry.svgSource is IconSvgSource.Remote &&
                    entry.svgSource.uri
                        .toString()
                        .endsWith(".svg")
            },
        )
    }

    @Test
    fun `remote source selects success or retry cache policy`() {
        val root = Files.createTempDirectory("icon-catalog-remote")

        try {
            Files.createDirectories(root.resolve("proprietary"))
            val context = IconCatalogContext.from(root)
            val success =
                TbankCdnIconCatalogSource {
                    """{"icons":{"fancy/small":["clock"]}}"""
                }.load(context)
            val empty =
                TbankCdnIconCatalogSource { """{"icons":{}}""" }
                    .load(context)
            val missing =
                TbankCdnIconCatalogSource { null }
                    .load(context)
            val failed =
                TbankCdnIconCatalogSource { error("offline") }
                    .load(context)

            assertEquals(IconCatalogCachePolicy.REMOTE_SUCCESS, success.cachePolicy)
            assertEquals(listOf("@tui.fancy.small.clock"), success.catalog.names)
            assertEquals(IconCatalogCachePolicy.REMOTE_RETRY, empty.cachePolicy)
            assertEquals(IconCatalogCachePolicy.REMOTE_RETRY, missing.cachePolicy)
            assertEquals(IconCatalogCachePolicy.REMOTE_RETRY, failed.cachePolicy)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `local scanner handles missing nested uppercase svg and ignored files`() {
        val root = Files.createTempDirectory("icon-catalog-local")
        val missing = root.resolve("missing")
        val icons = root.resolve("icons")

        try {
            assertEquals(
                emptyList<String>(),
                LocalIconCatalogScanner().load(missing).catalog.names,
            )

            write(icons.resolve("a.svg"), "<svg/>")
            write(icons.resolve("nested/B.SVG"), "<svg/>")
            write(icons.resolve("nested/readme.txt"), "ignored")

            val result = LocalIconCatalogScanner().load(icons)

            assertEquals(IconCatalogCachePolicy.LOCAL, result.cachePolicy)
            assertEquals(
                listOf("@tui.a", "@tui.nested.B"),
                result.catalog.names,
            )
            assertTrue(result.catalog.svgSource("@tui.a") is IconSvgSource.Local)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `catalog context uses physical scope when discovery root exists`() {
        val root = Files.createTempDirectory("icon-context-physical")

        try {
            val scope =
                TaigaUiPackageScope(
                    workspaceRoot = root.parent,
                    discoveryRoot = root,
                    packages = emptyMap(),
                    identity = "physical",
                    contentVersion = "1",
                )
            val context = IconCatalogContext.from(scope)

            assertEquals(root.toAbsolutePath().normalize(), context.scopeRoot)
            assertEquals(root.toAbsolutePath().normalize(), context.physicalScopeRoot)
            assertEquals(root.resolve("icons/src").toAbsolutePath().normalize(), context.publicIconsRoot)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `catalog context uses located package roots for virtual scope`() {
        val root = Files.createTempDirectory("icon-context-virtual")
        val discovery = root.resolve(".pnp.cjs")
        val iconsRoot = root.resolve("cache/icons")
        val tdsRoot = root.resolve("cache/tds-icons")
        val proprietaryRoot = root.resolve("cache/proprietary")
        val invalidation = root.resolve(".pnp.data.json")

        try {
            val packages =
                mapOf(
                    "@taiga-ui/icons" to located("@taiga-ui/icons", iconsRoot, root.resolve("icons.zip")),
                    "@taiga-ui/tds-icons" to located("@taiga-ui/tds-icons", tdsRoot, root.resolve("tds.zip")),
                    "@taiga-ui/proprietary" to
                        located(
                            "@taiga-ui/proprietary",
                            proprietaryRoot,
                            root.resolve("proprietary.zip"),
                        ),
                )
            val scope =
                TaigaUiPackageScope(
                    workspaceRoot = root,
                    discoveryRoot = discovery,
                    packages = packages,
                    identity = "virtual",
                    contentVersion = "1",
                    invalidationRoots = setOf(invalidation),
                )
            val context = IconCatalogContext.from(scope)

            assertNull(context.physicalScopeRoot)
            assertEquals(iconsRoot.resolve("src"), context.publicIconsRoot)
            assertEquals(tdsRoot.resolve("src"), context.tdsIconsRoot)
            assertEquals(proprietaryRoot, context.proprietaryPackageRoot)
            assertTrue(invalidation in context.invalidationRoots)
            assertTrue(root.resolve("icons.zip") in context.invalidationRoots)
            assertTrue(root.resolve("tds.zip") in context.invalidationRoots)
            assertTrue(root.resolve("proprietary.zip") in context.invalidationRoots)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `catalog context falls back to cache paths when virtual packages are absent`() {
        val root = Files.createTempDirectory("icon-context-virtual-fallback")

        try {
            val scope =
                TaigaUiPackageScope(
                    workspaceRoot = root,
                    discoveryRoot = root.resolve(".pnp.cjs"),
                    packages = emptyMap(),
                    identity = "virtual-fallback",
                    contentVersion = "1",
                )
            val context = IconCatalogContext.from(scope)
            val cacheKey = scope.cacheKey.toAbsolutePath().normalize()

            assertEquals(cacheKey, context.scopeRoot)
            assertNull(context.physicalScopeRoot)
            assertEquals(cacheKey.resolve("icons/src"), context.publicIconsRoot)
            assertEquals(cacheKey.resolve("tds-icons/src"), context.tdsIconsRoot)
            assertEquals(cacheKey.resolve("proprietary"), context.proprietaryPackageRoot)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `source support switches between public local tds and cdn`() {
        val root = Files.createTempDirectory("icon-source-support")

        try {
            val publicContext = IconCatalogContext.from(root)
            val public = PublicIconCatalogSource()
            val tds = TdsIconCatalogSource()
            val cdn = TbankCdnIconCatalogSource { null }

            assertTrue(public.supports(publicContext))
            assertFalse(tds.supports(publicContext))
            assertFalse(cdn.supports(publicContext))

            Files.createDirectories(publicContext.proprietaryPackageRoot)
            Files.createDirectories(publicContext.tdsIconsRoot)

            assertFalse(public.supports(publicContext))
            assertTrue(tds.supports(publicContext))
            assertTrue(cdn.supports(publicContext))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loader returns empty catalog when no source supports and clears resolved contexts`() {
        val root = Files.createTempDirectory("icon-loader-empty")

        try {
            val loader = IconCatalogLoader(emptyList())
            val result = loader.loadCatalogWithPolicy(root)

            assertEquals(emptyList<String>(), result.catalog.names)
            assertEquals(IconCatalogCachePolicy.LOCAL, result.cachePolicy)

            loader.clearResolvedContexts()

            assertFalse(loader.isAffected(root, root.resolve("unrelated.txt")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun located(
        name: String,
        root: Path,
        invalidationRoot: Path,
    ): LocatedTaigaUiPackage =
        LocatedTaigaUiPackage(
            name = name,
            root = root,
            realRoot = root,
            identity = name,
            contentVersion = "1",
            invalidationRoots = setOf(invalidationRoot),
        )

    private fun write(
        path: Path,
        content: String,
    ) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }
}
