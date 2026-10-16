package org.taigaui.designtokens.completion

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.taigaui.designtokens.documentation.DesignTokenHoverPopupModel
import org.taigaui.designtokens.project.DesignTokenIndexService
import java.nio.file.Files
import java.nio.file.Path

class DeprecatedDesignTokenIntegrationTest : BasePlatformTestCase() {
    private lateinit var tempRoot: Path
    private lateinit var indexService: DesignTokenIndexService

    override fun setUp() {
        super.setUp()
        tempRoot = Files.createTempDirectory("deprecated-design-token")
        indexService = project.getService(DesignTokenIndexService::class.java)
        indexService.clear()
        myFixture.enableInspections(DeprecatedDesignTokenInspection())
    }

    override fun tearDown() {
        try {
            indexService.clear()
            tempRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testColdInspectionWarmsCatalogAndRestartsInspection() {
        val workspace = createWorkspace("cold-inspection", deprecated = true)
        val sourcePath = workspace.resolve("src/component.less")
        val sourceFile = createFile(sourcePath, ".demo { color: var(--tui-legacy); }")

        myFixture.configureFromExistingVirtualFile(sourceFile)
        indexService.clear()

        myFixture.doHighlighting()

        repeat(500) {
            UIUtil.dispatchAllInvocationEvents()

            if (
                myFixture
                    .doHighlighting()
                    .any { info -> info.description?.startsWith(DEPRECATED_TOKEN_MESSAGE) == true }
            ) {
                assertTrue(indexService.isIndexCached(sourcePath))
                return
            }

            Thread.sleep(10)
        }

        fail("Deprecated token warning was not shown after the cold catalog warmup")
    }

    fun testKeepsDeprecatedTokenInCompletionAndMarksIt() {
        val workspace = createWorkspace("deprecated", deprecated = true)
        val sourcePath = workspace.resolve("src/component.less")
        val sourceFile = createFile(sourcePath, ".demo { color: var(--tui-leg); }")

        myFixture.configureFromExistingVirtualFile(sourceFile)
        val caretOffset =
            myFixture.editor.document.text
                .indexOf("--tui-leg") + 9

        myFixture.editor.caretModel.moveToOffset(caretOffset)
        indexService.completionTokenCatalog(sourcePath)
        myFixture.completeBasic()

        val item =
            requireNotNull(myFixture.lookupElements)
                .first { element -> element.lookupString == "--tui-legacy" }
        val presentation = LookupElementPresentation()

        item.renderElement(presentation)
        assertTrue(presentation.isStrikeout)
    }

    fun testHighlightsDeprecatedTokenAndOffersExplicitReplacement() {
        val workspace = createWorkspace("inspection", deprecated = true)
        val sourcePath = workspace.resolve("src/component.less")
        val sourceFile = createFile(sourcePath, ".demo { color: var(--tui-legacy); }")

        myFixture.configureFromExistingVirtualFile(sourceFile)
        val tokenOffset =
            myFixture.editor.document.text
                .indexOf("--tui-legacy")

        myFixture.editor.caretModel.moveToOffset(tokenOffset + 5)
        indexService.completionTokenCatalog(sourcePath)

        val problems =
            myFixture
                .doHighlighting()
                .filter { info -> info.description?.startsWith(DEPRECATED_TOKEN_MESSAGE) == true }

        assertEquals(1, problems.size)
        val quickFix = myFixture.findSingleIntention("Replace with --tui-text-primary")

        myFixture.launchAction(quickFix)
        assertEquals(
            ".demo { color: var(--tui-text-primary); }",
            myFixture.editor.document.text,
        )
    }

    fun testShowsDeprecationInHoverModel() {
        val workspace = createWorkspace("hover", deprecated = true)
        val sourcePath = workspace.resolve("src/component.less")

        createFile(sourcePath, ".demo { color: var(--tui-legacy); }")

        val groups = indexService.resolveToken(sourcePath, "--tui-legacy")
        val model = DesignTokenHoverPopupModel.create("--tui-legacy", groups)

        assertEquals("--tui-text-primary", model.deprecation?.replacement)
        assertEquals("use --tui-text-primary instead", model.deprecation?.message)
    }

    fun testProjectOverrideSuppressesPackageDeprecation() {
        val workspace = createWorkspace("override", deprecated = true)
        val sourcePath = workspace.resolve("src/component.less")

        createFile(
            workspace.resolve("project.json"),
            """
            {
              "targets": {
                "build": {
                  "options": {
                    "styles": ["src/styles.less"]
                  }
                }
              }
            }
            """.trimIndent(),
        )
        createFile(workspace.resolve("src/styles.less"), ":root { --tui-legacy: hotpink; }")
        createFile(sourcePath, ".demo { color: var(--tui-legacy); }")

        val entry =
            indexService
                .completionTokenCatalog(sourcePath)
                .single { candidate -> candidate.name == "--tui-legacy" }

        assertNull(entry.deprecation)
    }

    fun testDifferentPackageContextsKeepDifferentDeprecationState() {
        val deprecatedWorkspace = createWorkspace("v1", deprecated = true)
        val currentWorkspace = createWorkspace("v2", deprecated = false)
        val deprecatedSource = deprecatedWorkspace.resolve("src/component.less")
        val currentSource = currentWorkspace.resolve("src/component.less")

        createFile(deprecatedSource, ".demo { color: var(--tui-legacy); }")
        createFile(currentSource, ".demo { color: var(--tui-legacy); }")

        val deprecatedEntry =
            indexService
                .completionTokenCatalog(deprecatedSource)
                .single { entry -> entry.name == "--tui-legacy" }
        val currentEntry =
            indexService
                .completionTokenCatalog(currentSource)
                .single { entry -> entry.name == "--tui-legacy" }

        assertNotNull(deprecatedEntry.deprecation)
        assertNull(currentEntry.deprecation)
    }

    private fun createWorkspace(
        name: String,
        deprecated: Boolean,
    ): Path {
        val workspace = tempRoot.resolve(name)
        val packageRoot = workspace.resolve("node_modules/@taiga-ui/design-tokens")
        val deprecationComment =
            if (deprecated) {
                "/** @deprecated use --tui-text-primary instead */\n    "
            } else {
                ""
            }

        createFile(workspace.resolve("package.json"), "{}")
        createFile(
            packageRoot.resolve("package.json"),
            """{"name":"@taiga-ui/design-tokens","version":"${if (deprecated) "0.1.0" else "0.2.0"}"}""",
        )
        createFile(
            packageRoot.resolve("tokens.css"),
            """
            :root {
                $deprecationComment--tui-legacy: #000;
                --tui-legacy-secondary: #111;
                --tui-text-primary: #222;
            }
            """.trimIndent(),
        )

        return workspace
    }

    private fun createFile(
        path: Path,
        content: String,
    ): VirtualFile {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)

        return requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
    }

    private companion object {
        const val DEPRECATED_TOKEN_MESSAGE = "Deprecated Taiga UI design token"
    }
}
