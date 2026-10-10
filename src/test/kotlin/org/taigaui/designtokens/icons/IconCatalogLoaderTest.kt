package org.taigaui.designtokens.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class IconCatalogLoaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `maps only public svg paths when proprietary package is absent`() {
        val workspace = workspace()
        var remoteRequested = false

        createIcon(workspace, "icons/src/a-arrow-down.svg")
        createIcon(workspace, "icons/src/flags/ab.svg")
        createIcon(workspace, "tds-icons/src/fancy/medium/info-circle.svg")

        val names =
            load(
                workspace = workspace,
                fetcher =
                    IconCatalogFetcher {
                        remoteRequested = true
                        null
                    },
            )

        assertFalse(remoteRequested)
        assertEquals(
            setOf(
                "@tui.a-arrow-down",
                "@tui.flags.ab",
            ),
            names.toSet(),
        )
    }

    @Test
    fun `uses only installed tds icons when proprietary package is present`() {
        val workspace = workspace()
        var remoteRequested = false

        createPackage(workspace, "proprietary")
        createIcon(workspace, "icons/src/a-arrow-down.svg")
        createIcon(workspace, "tds-icons/src/fancy/medium/info-circle.svg")
        createIcon(workspace, "tds-icons/src/fancy/medium/alert.svg")
        createIcon(workspace, "tds-icons/src/fancy/medium/check-circle.svg")

        val names =
            load(
                workspace = workspace,
                fetcher =
                    IconCatalogFetcher {
                        remoteRequested = true
                        null
                    },
            )

        assertFalse(remoteRequested)
        assertFalse("@tui.a-arrow-down" in names)
        assertEquals(
            setOf(
                "@tui.fancy.medium.info-circle",
                "@tui.fancy.medium.alert",
                "@tui.fancy.medium.check-circle",
            ),
            names.toSet(),
        )
    }

    @Test
    fun `falls back to only tbank icon catalog when proprietary package has no tds icons`() {
        val workspace = workspace()

        createPackage(workspace, "proprietary")
        createIcon(workspace, "icons/src/a-arrow-down.svg")

        val names =
            load(
                workspace = workspace,
                fetcher =
                    IconCatalogFetcher {
                        """
                        {
                          "version": "v1",
                          "icons": {
                            "emoji": ["bank"],
                            "fancy/medium": ["air-hockey", "info-circle", "alert", "check-circle"]
                          }
                        }
                        """.trimIndent()
                    },
            )

        assertFalse("@tui.a-arrow-down" in names)
        assertEquals(
            setOf(
                "@tui.emoji.bank",
                "@tui.fancy.medium.air-hockey",
                "@tui.fancy.medium.info-circle",
                "@tui.fancy.medium.alert",
                "@tui.fancy.medium.check-circle",
            ),
            names.toSet(),
        )
    }

    @Test
    fun `derives remote svg url from tbank icon group and name`() {
        val entry =
            TbankIconCatalogParser
                .parseEntries(
                    """
                    {
                      "version": "v1",
                      "icons": {
                        "pragmatic/small": ["chevron-down"]
                      }
                    }
                    """.trimIndent(),
                ).single()

        assertEquals("@tui.pragmatic.small.chevron-down", entry.name)
        assertEquals(
            "$ICONS_BASE_URL/pragmatic/small/chevron-down.svg",
            entry.svgSource.uri.toString(),
        )
    }

    @Test
    fun `trims whitespace from CDN group and icon names before building svg url`() {
        val entry =
            TbankIconCatalogParser
                .parseEntries(
                    """
                    {
                      "version": "v1",
                      "icons": {
                        " fancy/medium ": ["logo-diameter "]
                      }
                    }
                    """.trimIndent(),
                ).single()

        assertEquals("@tui.fancy.medium.logo-diameter", entry.name)
        assertEquals(
            "$ICONS_BASE_URL/fancy/medium/logo-diameter.svg",
            entry.svgSource.uri.toString(),
        )
    }

    @Test
    fun `returns an empty local catalog when no icon source supports the workspace`() {
        val result =
            IconCatalogLoader(sources = emptyList())
                .loadCatalogWithPolicy(workspace())

        assertEquals(emptyList<String>(), result.catalog.names)
        assertEquals(IconCatalogCachePolicy.LOCAL, result.cachePolicy)
    }

    private fun load(
        workspace: Path,
        fetcher: IconCatalogFetcher = IconCatalogFetcher { null },
    ): List<String> {
        val sourceFile = workspace.resolve("src/app.ts")

        Files.createDirectories(sourceFile.parent)
        Files.writeString(sourceFile, "const icon = '@tui.';")

        val loader = IconCatalogLoader(fetcher)
        val scopeRoot = requireNotNull(loader.resolveScopeRoot(sourceFile))

        return loader.load(scopeRoot)
    }

    private fun workspace(): Path = temporaryFolder.newFolder().toPath()

    private fun createPackage(
        workspace: Path,
        name: String,
    ) {
        val packageRoot = workspace.resolve("node_modules/@taiga-ui/$name")

        Files.createDirectories(packageRoot)
        Files.writeString(packageRoot.resolve("package.json"), "{\"name\":\"@taiga-ui/$name\"}")
    }

    private fun createIcon(
        workspace: Path,
        relativePath: String,
    ) {
        val file = workspace.resolve("node_modules/@taiga-ui/$relativePath")

        Files.createDirectories(file.parent)
        Files.writeString(file, "<svg></svg>")
    }
}
