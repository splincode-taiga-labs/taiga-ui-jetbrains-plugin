package org.taigaui.designtokens.project

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.taigaui.designtokens.documentation.DesignTokenHoverPopupModel
import org.taigaui.designtokens.documentation.discovery.TaigaTokenExplorerCatalog
import org.taigaui.designtokens.index.PROJECT_STYLES_PACKAGE
import org.taigaui.designtokens.resolution.DesignTokenValueResolution
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class RealProjectValidationFixturesTest : BasePlatformTestCase() {
    private lateinit var tempRoot: Path
    private lateinit var service: DesignTokenIndexService

    override fun setUp() {
        super.setUp()
        tempRoot = Files.createTempDirectory("real-project-validation")
        service = project.getService(DesignTokenIndexService::class.java)
        service.clear()
    }

    override fun tearDown() {
        try {
            service.clear()
            tempRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testAngularNpmTaigaV5FixtureUsesAngularJsonAndProjectOverrides() {
        val workspace = copyFixture("angular-npm-taiga-v5")

        installDesignTokensPackage(
            workspace = workspace,
            version = "0.322.0",
            packageMarker = "#555555",
        )
        refresh(workspace)

        val sourcePath = workspace.resolve("src/app/app.component.less")
        val context = service.contextKey(sourcePath)

        assertTrue(context.projectEntryFiles.contains(workspace.resolve("src/styles.less").normalized()))
        assertEquals("#5c6ac4", resolvedValue(sourcePath, "--tui-validation-alias"))

        val model =
            DesignTokenHoverPopupModel.create(
                "--tui-text-secondary",
                service.resolveToken(sourcePath, "--tui-text-secondary"),
            )
        val projectSection = model.sections.single { section -> section.packageName == PROJECT_STYLES_PACKAGE }

        assertEquals("#5c6ac4", projectSection.rows.single().resolvedValue)

        val catalog = service.completionTokenCatalog(sourcePath)
        val deprecated = catalog.single { entry -> entry.name == "--tui-validation-old" }

        assertEquals("--tui-validation-alias", deprecated.deprecation?.replacement)
        assertTrue(catalog.none { entry -> entry.name == "--tui-validation-missing" })
        assertTrue(catalog.any { entry -> entry.name == PACKAGE_CONTEXT_TOKEN })
    }

    fun testNxPnpmTaigaMixedFixtureKeepsV4AndV5ContextsIsolated() {
        val workspace = copyFixture("nx-pnpm-taiga-mixed")
        val v4Root = workspace.resolve("apps/taiga-v4")
        val v5Root = workspace.resolve("apps/taiga-v5")

        installDesignTokensPackage(
            workspace = v4Root,
            version = "0.248.0",
            packageMarker = "#444444",
        )
        installDesignTokensPackage(
            workspace = v5Root,
            version = "0.322.0",
            packageMarker = "#555555",
        )
        refresh(workspace)

        val v4Source = v4Root.resolve("src/app/app.component.less")
        val v5Source = v5Root.resolve("src/app/app.component.less")
        val v4Context = service.contextKey(v4Source)
        val v5Context = service.contextKey(v5Source)

        assertTrue(v4Context.projectEntryFiles.contains(v4Root.resolve("src/styles.less").normalized()))
        assertTrue(v5Context.projectEntryFiles.contains(v5Root.resolve("src/styles.less").normalized()))
        assertEquals(v4Root.resolve(DESIGN_TOKENS_PACKAGE_PATH).normalized(), v4Context.packageRoot)
        assertEquals(v5Root.resolve(DESIGN_TOKENS_PACKAGE_PATH).normalized(), v5Context.packageRoot)
        assertFalse(v4Context.packageRoot == v5Context.packageRoot)

        assertEquals("#b54708", resolvedValue(v4Source, "--tui-validation-alias"))
        assertEquals("#067647", resolvedValue(v5Source, "--tui-validation-alias"))
        assertEquals("#444444", resolvedValue(v4Source, PACKAGE_CONTEXT_TOKEN))
        assertEquals("#555555", resolvedValue(v5Source, PACKAGE_CONTEXT_TOKEN))

        assertExplorerValues(v4Root, "#b54708", "#444444")
        assertExplorerValues(v5Root, "#067647", "#555555")
    }

    private fun assertExplorerValues(
        appRoot: Path,
        projectValue: String,
        packageValue: String,
    ) {
        val explorer = TaigaTokenExplorerCatalog.load(service, appRoot.resolve("src/app/app.component.less"))
        val entry = explorer.search("  TEXT-SECONDARY  ").single()
        val sections = explorer.details(entry).sections
        val projectRow = sections.single { section -> section.packageName == PROJECT_STYLES_PACKAGE }.rows.single()
        val packageRow = sections.single { section -> section.packageName == "@taiga-ui/design-tokens" }.rows.single()

        assertEquals(DesignTokenCatalogSource.PROJECT_OVERRIDE, entry.source)
        assertEquals(projectValue, projectRow.resolvedValue)
        assertNotNull(projectRow.color)
        assertEquals(appRoot.resolve("src/styles.less").normalized(), projectRow.navigationTarget?.sourceFile)
        assertEquals("#111111", packageRow.resolvedValue)
        assertNotNull(packageRow.overrideMessage)
        assertTrue(explorer.search("unknown-fixture-token").isEmpty())
        assertEquals(
            listOf("--tui-package-${appRoot.fileName}"),
            explorer.search("--tui-package-taiga-").map(DesignTokenCatalogEntry::name),
        )

        val marker = explorer.search(PACKAGE_CONTEXT_TOKEN).single()
        val markerSection = explorer.details(marker).sections.single()

        assertEquals(packageValue, markerSection.rows.single().resolvedValue)
    }

    private fun copyFixture(name: String): Path {
        val source = REPOSITORY_ROOT.resolve("test-fixtures/real-projects/$name")
        val target = tempRoot.resolve(name)

        assertTrue("Missing checked-in fixture: $source", Files.isDirectory(source))

        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val relative = source.relativize(path)
                val destination = target.resolve(relative.toString())

                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }

        return target
    }

    private fun installDesignTokensPackage(
        workspace: Path,
        version: String,
        packageMarker: String,
    ) {
        val packageRoot = workspace.resolve(DESIGN_TOKENS_PACKAGE_PATH)

        createFile(
            packageRoot.resolve("package.json"),
            """{"name":"@taiga-ui/design-tokens","version":"$version"}""",
        )
        createFile(
            packageRoot.resolve("tokens.css"),
            """
            :root {
                --tui-text-secondary: #111111;
                $PACKAGE_CONTEXT_TOKEN: $packageMarker;
                --tui-package-${workspace.fileName}: $packageMarker;
            }
            """.trimIndent(),
        )
    }

    private fun resolvedValue(
        sourcePath: Path,
        tokenName: String,
    ): String =
        service
            .resolveToken(sourcePath, tokenName)
            .flatMap { group -> group.resolutions }
            .mapNotNull { resolution ->
                (resolution.result as? DesignTokenValueResolution.Resolved)?.value
            }.distinct()
            .single()

    private fun createFile(
        path: Path,
        content: String,
    ) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
    }

    private fun refresh(root: Path) {
        val rootFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root))

        VfsUtil.markDirtyAndRefresh(false, true, true, rootFile)
    }

    private fun Path.normalized(): Path = toAbsolutePath().normalize()

    private companion object {
        val REPOSITORY_ROOT: Path = Path.of("").toAbsolutePath().normalize()
        val DESIGN_TOKENS_PACKAGE_PATH: Path = Path.of("node_modules/@taiga-ui/design-tokens")
        const val PACKAGE_CONTEXT_TOKEN = "--tui-package-context-marker"
    }
}
