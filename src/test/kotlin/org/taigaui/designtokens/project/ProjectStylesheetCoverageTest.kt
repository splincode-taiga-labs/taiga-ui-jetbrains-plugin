package org.taigaui.designtokens.project

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.taigaui.designtokens.index.DesignTokenSourceExtractor
import org.taigaui.designtokens.psi.PsiDesignTokenSourceExtractor
import org.taigaui.designtokens.packageinfo.DesignTokenSourcePackage
import org.taigaui.designtokens.packageinfo.DesignTokensPackage
import org.taigaui.designtokens.packageinfo.DesignTokensPackageResolver
import java.nio.file.Files
import java.nio.file.Path

class ProjectStylesheetCoverageTest : BasePlatformTestCase() {
    private lateinit var workspaceRoot: Path

    override fun setUp() {
        super.setUp()
        workspaceRoot = Files.createTempDirectory("project-styles-coverage")
    }

    override fun tearDown() {
        try {
            workspaceRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testProviderCreatesStandaloneRequestWithoutInstalledPackage() {
        val sourceFile = workspaceRoot.resolve("src/standalone.css")

        Files.createDirectories(sourceFile.parent)
        Files.writeString(sourceFile, ".demo {}")

        val provider =
            ProjectStylesheetIndexProvider(
                project = project,
                packageResolver = DesignTokensPackageResolver(),
                sourceExtractor = PsiDesignTokenSourceExtractor(project),
            )
        val request = requireNotNull(provider.request(sourceFile))

        assertTrue(sourceFile.toAbsolutePath().normalize() in request.entryFiles)
    }

    fun testPathResolverSupportsExternalPackagePartialAndIndexForms() {
        val partial = workspaceRoot.resolve("styles/_theme.scss")
        val index = workspaceRoot.resolve("styles/components/index.less")

        Files.createDirectories(partial.parent)
        Files.writeString(partial, ":root {}")
        Files.createDirectories(index.parent)
        Files.writeString(index, ":root {}")

        assertEquals(partial, ProjectStylesheetPathResolver.resolveSourceFile(workspaceRoot.resolve("styles/theme")))
        assertEquals(index, ProjectStylesheetPathResolver.resolveSourceFile(workspaceRoot.resolve("styles/components")))
        assertTrue(ProjectStylesheetPathResolver.isExternalImport("SASS:color"))
        assertTrue(ProjectStylesheetPathResolver.isExternalImport("DATA:text/css,body{}"))
        assertTrue(ProjectStylesheetPathResolver.isPackageImport("@taiga-ui/core/styles"))
        assertTrue(ProjectStylesheetPathResolver.isPackageImport("node_modules/pkg/styles.css"))
        assertFalse(ProjectStylesheetPathResolver.isStylesheet(Path.of("/")))
    }

    fun testConfigurationReaderFiltersExternalMissingEscapingAndNodeModulesEntries() {
        val projectRoot = workspaceRoot.resolve("apps/demo")
        val configFile = projectRoot.resolve("project.json")
        val localStyle = projectRoot.resolve("src/styles.css")
        val workspaceStyle = workspaceRoot.resolve("shared/theme.less")
        val nodeModulesStyle = workspaceRoot.resolve("node_modules/pkg/styles.css")

        Files.createDirectories(localStyle.parent)
        Files.writeString(localStyle, ":root {}")
        Files.createDirectories(workspaceStyle.parent)
        Files.writeString(workspaceStyle, ":root {}")
        Files.createDirectories(nodeModulesStyle.parent)
        Files.writeString(nodeModulesStyle, ":root {}")

        val content =
            """
            {
              "styles": [
                "src/styles.css",
                "../../shared/theme.less",
                "https://example.com/theme.css",
                "@taiga-ui/core/styles.css",
                "../../node_modules/pkg/styles.css",
                "../../../outside.css",
                "missing.scss"
              ]
            }
            """.trimIndent()
        val reader =
            ProjectStylesheetConfigurationReader(
                readText = { path -> content.takeIf { path == configFile } },
                parser = ProjectStylesheetJsonPsiParser(project),
            )

        assertEquals(
            listOf(listOf(localStyle, workspaceStyle)),
            reader.readStyleGroups(configFile, projectRoot, workspaceRoot),
        )
        assertEmpty(
            reader.readStyleGroups(
                configFile.resolveSibling("missing.json"),
                projectRoot,
                workspaceRoot,
            ),
        )
    }

    fun testImportParserExtractsQuotedUseForwardAndUrlForms() {
        assertEquals(
            listOf(
                "./base.css",
                "./theme.less",
                "./tokens.scss",
                "./forwarded",
                "./url-theme.css",
            ),
            ProjectStylesheetImportParser.parse(
                """
                @import "./base.css", './theme.less';
                @use "./tokens.scss";
                @forward './forwarded';
                @import url(./url-theme.css);
                """.trimIndent(),
            ),
        )
    }

    fun testImportResolverResolvesLocalProjectAndWorkspaceStylesAndFiltersInvalidImports() {
        val projectRoot = workspaceRoot.resolve("apps/demo")
        val sourceFile = projectRoot.resolve("src/component.scss")
        val localPartial = projectRoot.resolve("src/_local.scss")
        val projectStyle = projectRoot.resolve("styles/project.css")
        val workspaceStyle = workspaceRoot.resolve("shared/_theme.less")
        val nodeModulesStyle = workspaceRoot.resolve("node_modules/pkg/styles.css")

        Files.createDirectories(sourceFile.parent)
        Files.writeString(sourceFile, ".demo {}")
        Files.writeString(localPartial, ":root {}")
        Files.createDirectories(projectStyle.parent)
        Files.writeString(projectStyle, ":root {}")
        Files.createDirectories(workspaceStyle.parent)
        Files.writeString(workspaceStyle, ":root {}")
        Files.createDirectories(nodeModulesStyle.parent)
        Files.writeString(nodeModulesStyle, ":root {}")

        val resolver =
            ProjectStylesheetImportResolver {
                listOf(
                    "./local",
                    "/styles/project.css?raw",
                    "/shared/theme#palette",
                    "",
                    "https://example.com/theme.css",
                    "sass:color",
                    "~@taiga-ui/core/styles",
                    "../../../outside.css",
                    "../../../node_modules/pkg/styles.css",
                )
            }

        assertEquals(
            listOf(
                localPartial.toAbsolutePath().normalize(),
                projectStyle.toAbsolutePath().normalize(),
                workspaceStyle.toAbsolutePath().normalize(),
            ),
            resolver.resolveImports(
                sourceFile = sourceFile,
                projectRoot = projectRoot,
                workspaceRoot = workspaceRoot,
            ),
        )
    }

    fun testCurrentFileEntrypointProviderAcceptsOnlyWorkspaceStylesheets() {
        val provider = CurrentFileProjectStylesheetEntrypointProvider()
        val projectRoot = workspaceRoot.resolve("apps/demo")
        val style = projectRoot.resolve("src/styles.scss")
        val script = projectRoot.resolve("src/app.ts")
        val dependencyStyle = workspaceRoot.resolve("node_modules/pkg/styles.css")

        fun context(sourceFile: Path) =
            ProjectStylesheetEntrypointContext(
                sourceFile = sourceFile,
                projectRoot = projectRoot,
                workspaceRoot = workspaceRoot,
            )

        assertEquals(listOf(style), provider.find(context(style)))
        assertEmpty(provider.find(context(script)))
        assertEmpty(provider.find(context(dependencyStyle)))
    }

    fun testProviderRejectsInstalledSourceAndUsesDiscoveryRootAsWorkspaceHint() {
        val packageRoot = workspaceRoot.resolve("node_modules/@taiga-ui/core")
        val installedStyle = packageRoot.resolve("styles/theme.css")
        val appSource = workspaceRoot.resolve("apps/demo/src/component.css")
        val packageInfo =
            DesignTokensPackage(
                root = packageRoot,
                realRoot = packageRoot,
                version = "5.0.0",
                discoveryRoot = workspaceRoot.resolve("node_modules/@taiga-ui"),
                sourcePackages =
                    listOf(
                        DesignTokenSourcePackage(
                            name = "@taiga-ui/core",
                            root = packageRoot,
                            realRoot = packageRoot,
                            version = "5.0.0",
                            sourceRoots = listOf(packageRoot.resolve("styles")),
                        ),
                    ),
            )
        val provider =
            ProjectStylesheetIndexProvider(
                project = project,
                packageResolver = DesignTokensPackageResolver(),
                sourceExtractor = DesignTokenSourceExtractor { emptyList() },
            )

        assertNull(provider.request(installedStyle, packageInfo))

        val request = requireNotNull(provider.request(appSource, packageInfo))

        assertEquals(workspaceRoot.toAbsolutePath().normalize(), request.workspaceRoot)
    }

    fun testProviderRefreshesNewFilesAndFallsBackToDiskText() {
        val source = workspaceRoot.resolve("src/new-file.css")
        Files.createDirectories(source.parent)
        Files.writeString(source, ":root { --tui-new: red; }")
        val provider =
            ProjectStylesheetIndexProvider(
                project = project,
                packageResolver = DesignTokensPackageResolver(),
                sourceExtractor = DesignTokenSourceExtractor { emptyList() },
            )
        val modificationStamp =
            provider.javaClass
                .getDeclaredMethod("modificationStamp", Path::class.java)
                .apply { isAccessible = true }
                .invoke(provider, source) as Long?
        val readText =
            provider.javaClass
                .getDeclaredMethod("readProjectText", Path::class.java)
                .apply { isAccessible = true }
                .invoke(provider, source) as String?

        assertNotNull(modificationStamp)
        assertEquals(":root { --tui-new: red; }", readText)

        val missing = workspaceRoot.resolve("src/missing.css")

        assertNull(
            provider.javaClass
                .getDeclaredMethod("modificationStamp", Path::class.java)
                .apply { isAccessible = true }
                .invoke(provider, missing),
        )
        assertNull(
            provider.javaClass
                .getDeclaredMethod("readProjectText", Path::class.java)
                .apply { isAccessible = true }
                .invoke(provider, missing),
        )
    }

    fun testJsonParserHandlesEmptyNestedAndMixedStyleArrays() {
        val parser = ProjectStylesheetJsonPsiParser(project)

        assertEmpty(parser.parseStyleGroups(""))
        assertEmpty(parser.parseStyleGroups("""{"styles": []}"""))
        assertEquals(
            listOf(listOf("a.css", "b.less", "c.scss")),
            parser.parseStyleGroups(
                """
                {
                  "styles": [
                    "README.md",
                    "a.css",
                    {"input": "b.less", "nested": ["c.scss", "ignore.txt"]},
                    42,
                    null
                  ]
                }
                """.trimIndent(),
            ),
        )
    }
}
