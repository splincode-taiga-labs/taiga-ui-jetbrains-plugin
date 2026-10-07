package org.taigaui.designtokens.project

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.taigaui.designtokens.index.DesignTokenDeclaration
import org.taigaui.designtokens.index.DesignTokenIndex
import org.taigaui.designtokens.index.DesignTokenPlatform
import org.taigaui.designtokens.index.PROJECT_STYLES_PACKAGE
import org.taigaui.designtokens.resolution.DesignTokenValueResolution
import java.nio.file.Files
import java.nio.file.Path

class DesignTokenIndexServiceTest : BasePlatformTestCase() {
    private lateinit var tempRoot: Path
    private lateinit var service: DesignTokenIndexService
    private lateinit var fixture: PackageFixture

    override fun setUp() {
        super.setUp()
        tempRoot = Files.createTempDirectory("design-token-index-service")
        service = project.getService(DesignTokenIndexService::class.java)
        service.clear()
        fixture = createPackage("workspace", "#fff")
    }

    override fun tearDown() {
        try {
            service.clear()
            tempRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testCachesRepeatedRequestsUntilTokenFileChanges() {
        val first = index(fixture)
        val second = index(fixture)

        assertSame(first, second)
        assertEquals(1, service.cachedPackageCount)

        writeToken(fixture.tokenFile, "#000")

        assertEquals(0, service.cachedPackageCount)

        val rebuilt = index(fixture)

        assertNotSame(first, rebuilt)
        assertEquals("#000", tokenValue(rebuilt))
        assertSame(rebuilt, index(fixture))
        assertEquals(1, service.cachedPackageCount)
    }

    fun testInvalidatesCachedIndexWhenPackageDocumentChangesBeforeSave() {
        val first = index(fixture)
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(fixture.tokenFile))

        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(":root { --tui-background-base: #123; }")
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)

        assertEquals(0, service.cachedPackageCount)

        val rebuilt = index(fixture)

        assertNotSame(first, rebuilt)
        assertEquals("#123", tokenValue(rebuilt))
    }

    fun testResolvesTokenDeclaredInCurrentProjectStylesheet() {
        val sourcePath = tempRoot.resolve("workspace/src/component.css")

        createFile(
            sourcePath,
            """
            :host {
                --tui-test: red;
            }

            :host {
                color: var(--tui-test);
            }
            """.trimIndent(),
        )

        val resolutions =
            service
                .resolveToken(sourcePath, "--tui-test")
                .flatMap { group -> group.resolutions }

        assertTrue(resolutions.isNotEmpty())
        assertTrue(
            resolutions.any { resolution ->
                (resolution.result as? DesignTokenValueResolution.Resolved)?.value == "red"
            },
        )
        assertEquals(
            setOf(PROJECT_STYLES_PACKAGE),
            resolutions
                .flatMap { resolution -> resolution.variant.origins }
                .mapNotNull { origin -> origin.packageName }
                .toSet(),
        )
    }

    fun testKeepsCachedIndexForUnrelatedProjectAndPackageFiles() {
        val first = index(fixture)

        writeFile(fixture.sourceFile, "Application source updated")
        val readme = createVfsFile(fixture.packageRoot.resolve("README.md"), "Docs")
        writeFile(readme, "Updated docs")

        assertSame(first, index(fixture))
        assertEquals(1, service.cachedPackageCount)
    }

    fun testInvalidatesWhenNewStylesheetIsCreated() {
        val first = index(fixture)

        createVfsFile(
            fixture.packageRoot.resolve("palette/dark.scss"),
            "[tuiTheme='dark'] { --tui-background-base: #000; }",
        )

        assertEquals(0, service.cachedPackageCount)

        val rebuilt = index(fixture)

        assertNotSame(first, rebuilt)
        assertEquals(2, rebuilt.find(TOKEN_NAME).size)
    }

    fun testInvalidatesWhenLessFileChanges() {
        val lessTokenName = "--tui-less-test"
        val lessFile =
            createVfsFile(
                fixture.packageRoot.resolve("angular/desktop.less"),
                ":root { $lessTokenName: #111; }",
            )
        val first = index(fixture)

        assertEquals("#111", first.find(lessTokenName).single().rawValue)

        writeFile(lessFile, ":root { $lessTokenName: #222; }")

        assertEquals(0, service.cachedPackageCount)

        val rebuilt = index(fixture)

        assertNotSame(first, rebuilt)
        assertEquals("#222", rebuilt.find(lessTokenName).single().rawValue)
    }

    fun testInvalidatesWhenStylesheetIsRenamedOrDeleted() {
        val first = index(fixture)

        rename(fixture.tokenFile, "dark.css")
        val afterRename = index(fixture)

        assertNotSame(first, afterRename)

        delete(fixture.tokenFile)
        val afterDelete = index(fixture)

        assertNotSame(afterRename, afterDelete)
        assertTrue(afterDelete.find(TOKEN_NAME).isEmpty())
    }

    fun testInvalidatesWhenStylesheetMovesToAnotherContext() {
        val mobileDirectory = createDirectory(fixture.packageRoot.resolve("mobile"))
        val first = index(fixture)

        move(fixture.tokenFile, mobileDirectory)

        assertEquals(0, service.cachedPackageCount)

        val rebuilt = index(fixture)
        val variant = rebuilt.find(TOKEN_NAME).single()

        assertNotSame(first, rebuilt)
        assertEquals(DesignTokenPlatform.MOBILE, variant.context.platform)
    }

    fun testInvalidatesWhenPackageVersionChanges() {
        val first = index(fixture)

        writeFile(
            fixture.packageJson,
            """{"name":"@taiga-ui/design-tokens","version":"0.311.0"}""",
        )

        val rebuilt = index(fixture)

        assertNotSame(first, rebuilt)
        assertEquals("#fff", tokenValue(rebuilt))
        assertEquals(1, service.cachedPackageCount)
    }

    fun testInvalidatesOnlyChangedPackageInMonorepo() {
        val firstPackage = createPackage("apps/first", "#111")
        val secondPackage = createPackage("apps/second", "#222")
        val firstIndex = index(firstPackage)
        val secondIndex = index(secondPackage)

        assertEquals(2, service.cachedPackageCount)

        writeToken(firstPackage.tokenFile, "#333")

        assertEquals(1, service.cachedPackageCount)

        val rebuiltFirst = index(firstPackage)
        val cachedSecond = index(secondPackage)

        assertNotSame(firstIndex, rebuiltFirst)
        assertEquals("#333", tokenValue(rebuiltFirst))
        assertSame(secondIndex, cachedSecond)
        assertEquals("#222", tokenValue(cachedSecond))
        assertEquals(2, service.cachedPackageCount)
    }

    fun testCompletionNamesIncludeInstalledCoreTokensOutsideProprietaryImportGraph() {
        val workspaceRoot = tempRoot.resolve("proprietary-workspace")
        val sourcePath = workspaceRoot.resolve("src/app.less")
        val scopeRoot = workspaceRoot.resolve("node_modules/@taiga-ui")
        val coreRoot = scopeRoot.resolve("core")
        val proprietaryRoot = scopeRoot.resolve("proprietary")

        createFile(sourcePath, ".demo { font: var(--tui-font-text-xs2); }")
        createFile(
            coreRoot.resolve("package.json"),
            taigaPackageJson("@taiga-ui/core", "4.21.0"),
        )
        createFile(
            coreRoot.resolve("styles/theme/variables.less"),
            ":root { $CORE_FONT_TOKEN: normal 0.6875rem/1rem sans-serif; }",
        )
        createFile(
            proprietaryRoot.resolve("package.json"),
            taigaPackageJson("@taiga-ui/proprietary", "4.21.0"),
        )
        createFile(
            proprietaryRoot.resolve("styles/theme.less"),
            ":root { --tui-proprietary-only: red; }",
        )

        val resolutionIndex = requireNotNull(service.getIndex(sourcePath))
        val completionNames = service.completionTokenNames(sourcePath)

        assertTrue(resolutionIndex.find(CORE_FONT_TOKEN).isEmpty())
        assertTrue(completionNames.contains(CORE_FONT_TOKEN))
    }

    fun testLocalOverridesResolveWithoutInstalledPackage() {
        val sourcePath = tempRoot.resolve("local-overrides/src/component.css")
        createFile(sourcePath, ".demo { color: var(--tui-local-color); }")
        val override =
            DesignTokenDeclaration(
                name = "--tui-local-color",
                value = "hotpink",
                sourceFile = sourcePath,
                line = 1,
            )

        val resolutions =
            service
                .resolveToken(
                    sourceFile = sourcePath,
                    tokenName = "--tui-local-color",
                    localOverrides = listOf(override),
                ).flatMap { group -> group.resolutions }

        assertTrue(resolutions.isNotEmpty())
        assertTrue(
            resolutions.any { resolution ->
                (resolution.result as? DesignTokenValueResolution.Resolved)?.value == "hotpink"
            },
        )
    }

    fun testServiceReturnsNullWhenNoInstalledPackageCanBeResolved() {
        val sourcePath = tempRoot.resolve("standalone/app.txt")
        createFile(sourcePath, "Application source")

        assertNull(service.getIndex(sourcePath))
        assertEquals(0, service.cachedPackageCount)
    }

    fun testDefensiveIndexBoundaryReturnsNullOnUnexpectedFailure() {
        val sourcePath = tempRoot.resolve("broken/app.css")
        val method =
            service.javaClass.declaredMethods
                .single { candidate ->
                    candidate.name == "indexOrNull" &&
                        candidate.parameterCount == 2
                }.apply { isAccessible = true }
        val operation: () -> DesignTokenIndex = { error("broken index") }

        assertNull(method.invoke(service, sourcePath, operation))
    }

    fun testContextKeyBoundaryFallsBackToSourceDirectoryOnUnexpectedFailure() {
        val sourcePath =
            tempRoot
                .resolve("fallback/src/app.css")
                .toAbsolutePath()
                .normalize()
        val method =
            service.javaClass.declaredMethods
                .single { candidate ->
                    candidate.name == "contextKeyOrFallback" &&
                        candidate.parameterCount == 2
                }.apply { isAccessible = true }
        val operation: () -> TokenContextKey = { error("broken resolver") }
        val key = method.invoke(service, sourcePath, operation) as TokenContextKey

        assertEquals(sourcePath.parent, key.workspaceRoot)
        assertEquals(sourcePath.parent, key.projectRoot)
        assertNull(key.packageRoot)
        assertEquals(listOf(sourcePath), key.projectEntryFiles)
    }

    private fun createPackage(
        workspace: String,
        tokenValue: String,
    ): PackageFixture {
        val workspaceRoot = tempRoot.resolve(workspace)
        val packageRoot = workspaceRoot.resolve("node_modules/@taiga-ui/design-tokens")
        val sourcePath = workspaceRoot.resolve("src/app.txt")

        return PackageFixture(
            packageRoot = packageRoot,
            sourcePath = sourcePath,
            sourceFile = createFile(sourcePath, "Application source"),
            packageJson =
                createFile(
                    packageRoot.resolve("package.json"),
                    """{"name":"@taiga-ui/design-tokens","version":"0.310.0"}""",
                ),
            tokenFile =
                createFile(
                    packageRoot.resolve("palette/light.css"),
                    ":root { --tui-background-base: $tokenValue; }",
                ),
        )
    }

    private fun index(packageFixture: PackageFixture): DesignTokenIndex =
        requireNotNull(
            service.getIndex(packageFixture.sourcePath),
        )

    private fun tokenValue(index: DesignTokenIndex): String =
        index
            .find(TOKEN_NAME)
            .single()
            .rawValue

    private fun writeToken(
        file: VirtualFile,
        value: String,
    ) {
        writeFile(file, ":root { --tui-background-base: $value; }")
    }

    private fun writeFile(
        file: VirtualFile,
        content: String,
    ) {
        WriteCommandAction.runWriteCommandAction(project) {
            VfsUtil.saveText(file, content)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun rename(
        file: VirtualFile,
        newName: String,
    ) {
        WriteCommandAction.runWriteCommandAction(project) {
            file.rename(this, newName)
        }
    }

    private fun move(
        file: VirtualFile,
        directory: VirtualFile,
    ) {
        WriteCommandAction.runWriteCommandAction(project) {
            file.move(this, directory)
        }
    }

    private fun delete(file: VirtualFile) {
        WriteCommandAction.runWriteCommandAction(project) {
            file.delete(this)
        }
    }

    private fun createVfsFile(
        path: Path,
        content: String,
    ): VirtualFile {
        Files.createDirectories(path.parent)

        val parent = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path.parent))
        val file =
            WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
                parent.findChild(path.fileName.toString())
                    ?: parent.createChildData(this, path.fileName.toString())
            }

        writeFile(file, content)

        return file
    }

    private fun createDirectory(path: Path): VirtualFile {
        Files.createDirectories(path)

        return requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path),
        )
    }

    private fun createFile(
        path: Path,
        content: String,
    ): VirtualFile {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)

        return requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path),
        )
    }

    private fun taigaPackageJson(
        name: String,
        version: String,
    ): String =
        """
        {
            "name": "$name",
            "version": "$version",
            "exports": {
                "./styles/*": "./styles/*"
            }
        }
        """.trimIndent()

    private data class PackageFixture(
        val packageRoot: Path,
        val sourcePath: Path,
        val sourceFile: VirtualFile,
        val packageJson: VirtualFile,
        val tokenFile: VirtualFile,
    )

    private companion object {
        const val TOKEN_NAME = "--tui-background-base"
        const val CORE_FONT_TOKEN = "--tui-font-text-xs"
    }
}
