package org.taigaui.designtokens.index

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.taigaui.designtokens.packageinfo.DesignTokensPackage
import java.nio.file.Files
import java.nio.file.Path

class DesignTokensPackageScannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `collects declarations from all supported package files`() {
        val packageRoot = temporaryFolder.newFolder("design-tokens").toPath()
        val css = createFile(packageRoot.resolve("a.css"))
        val less = createFile(packageRoot.resolve("themes/b.less"))
        createFile(packageRoot.resolve("index.js"))
        val declarationsByFile =
            mapOf(
                css.toAbsolutePath().normalize() to
                    listOf(declaration(css, "--tui-text-primary", "#000")),
                less.toAbsolutePath().normalize() to
                    listOf(
                        declaration(less, "--tui-text-primary", "#fff"),
                        declaration(less, "--tui-radius", "0.75rem"),
                    ),
            )
        val scanner =
            DesignTokensPackageScanner(
                sourceExtractor =
                    DesignTokenSourceExtractor { sourceFile ->
                        declarationsByFile[sourceFile].orEmpty()
                    },
            )
        val designTokensPackage =
            DesignTokensPackage(
                root = packageRoot,
                realRoot = packageRoot,
                version = "1.0.0",
            )

        val result = scanner.scan(designTokensPackage)
        val all = scanner.scanAll(designTokensPackage)

        assertEquals(result, all)
        assertEquals(
            listOf(
                "--tui-text-primary",
                "--tui-text-primary",
                "--tui-radius",
            ),
            result.map(DesignTokenDeclaration::name),
        )
        assertEquals(listOf("#000", "#fff", "0.75rem"), result.map(DesignTokenDeclaration::value))
    }

    private fun createFile(path: Path): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, "fixture")

        return path
    }

    private fun declaration(
        sourceFile: Path,
        name: String,
        value: String,
    ): DesignTokenDeclaration =
        DesignTokenDeclaration(
            name = name,
            value = value,
            sourceFile = sourceFile.toAbsolutePath().normalize(),
            line = 1,
        )
}
