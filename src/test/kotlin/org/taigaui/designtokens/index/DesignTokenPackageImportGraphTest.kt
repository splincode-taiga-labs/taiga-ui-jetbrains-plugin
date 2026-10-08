package org.taigaui.designtokens.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.taigaui.designtokens.packageinfo.DesignTokenSourcePackage
import java.nio.file.Files
import java.nio.file.Path

class DesignTokenPackageImportGraphTest {
    @Test
    fun `resolves relative extension directory and package imports and ignores unsupported imports`() {
        val root = Files.createTempDirectory("token-import-graph")
        val core = root.resolve("node_modules/@taiga-ui/core")
        val kit = root.resolve("node_modules/@taiga-ui/kit")
        val entry = core.resolve("styles/main.less")
        val local = core.resolve("styles/local.scss")
        val directoryIndex = core.resolve("styles/theme/index.css")
        val packageFile = kit.resolve("styles/tokens.css")

        write(local, ":root { --tui-local: red; }")
        write(directoryIndex, ":root { --tui-directory: blue; }")
        write(packageFile, ":root { --tui-package: green; }")
        write(
            entry,
            """
            @import "./local";
            @import "./theme";
            @import url("@taiga-ui/kit/styles/tokens.css?raw#fragment");
            @import "~@taiga-ui/kit/styles/tokens.css";
            @import "@taiga-ui/core";
            @import "@taiga-ui/missing/styles/nope.css";
            @import "HTTPS://example.com/theme.css";
            @import "DATA:text/css,body{}";
            @import "./missing";
            @import "./local";
            """.trimIndent(),
        )

        val result =
            DesignTokenPackageImportGraph().findReachableFiles(
                entryFiles = listOf(entry, entry, root.resolve("outside.css")),
                sourcePackages =
                    listOf(
                        sourcePackage("@taiga-ui/core", core),
                        sourcePackage("@taiga-ui/kit", kit),
                    ),
            )

        assertEquals(
            listOf(entry, local, directoryIndex)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .sortedBy(Path::toString),
            requireNotNull(result["@taiga-ui/core"]),
        )
        assertEquals(
            listOf(packageFile.toAbsolutePath().normalize()),
            requireNotNull(result["@taiga-ui/kit"]),
        )
        assertFalse(result.containsKey("@taiga-ui/missing"))
    }

    @Test
    fun `resolves direct files uppercase extensions and ignores empty external and short package imports`() {
        val root = Files.createTempDirectory("token-import-graph-branches")
        val core = root.resolve("node_modules/@taiga-ui/core")
        val entry = core.resolve("styles/main.css")
        val direct = core.resolve("styles/direct.LESS")

        write(direct, ":root { --tui-direct: red; }")
        write(
            entry,
            """
            @import "./direct.LESS";
            @import "";
            @import "~";
            @import "http://example.com/a.css";
            @import "data:text/css,body{}";
            @import "@taiga-ui/core";
            """.trimIndent(),
        )

        val result =
            DesignTokenPackageImportGraph().findReachableFiles(
                entryFiles = listOf(entry),
                sourcePackages = listOf(sourcePackage("@taiga-ui/core", core)),
            )

        assertEquals(
            listOf(entry, direct)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .sortedBy(Path::toString),
            requireNotNull(result["@taiga-ui/core"]),
        )
    }

    @Test
    fun `resolves index scss and import options syntax`() {
        val root = Files.createTempDirectory("token-import-graph-options")
        val core = root.resolve("node_modules/@taiga-ui/core")
        val entry = core.resolve("main.less")
        val theme = core.resolve("theme/index.scss")

        write(theme, ":root { --tui-theme: red; }")
        write(
            entry,
            """@import (reference) url("./theme");""",
        )

        val result =
            DesignTokenPackageImportGraph().findReachableFiles(
                entryFiles = listOf(entry),
                sourcePackages = listOf(sourcePackage("@taiga-ui/core", core)),
            )

        assertEquals(
            listOf(entry, theme)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .sortedBy(Path::toString),
            requireNotNull(result["@taiga-ui/core"]),
        )
    }

    @Test
    fun `prefers the deepest source package root and tolerates unreadable entry paths`() {
        val root = Files.createTempDirectory("token-import-graph-nested")
        val parent = root.resolve("packages")
        val nested = parent.resolve("nested")
        val nestedEntry = nested.resolve("tokens.css")
        val directoryEntry = nested.resolve("directory")

        write(nestedEntry, ":root { --tui-value: red; }")
        Files.createDirectories(directoryEntry)

        val result =
            DesignTokenPackageImportGraph().findReachableFiles(
                entryFiles = listOf(nestedEntry, directoryEntry),
                sourcePackages =
                    listOf(
                        sourcePackage("parent", parent),
                        sourcePackage("nested", nested),
                    ),
            )

        assertEquals(
            listOf(directoryEntry, nestedEntry)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .sortedBy(Path::toString),
            requireNotNull(result["nested"]),
        )
        assertFalse(result.containsKey("parent"))
    }

    private fun sourcePackage(
        name: String,
        root: Path,
    ): DesignTokenSourcePackage =
        DesignTokenSourcePackage(
            name = name,
            root = root,
            realRoot = root,
            version = "1.0.0",
            sourceRoots = listOf(root),
        )

    private fun write(
        path: Path,
        content: String,
    ) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }
}
