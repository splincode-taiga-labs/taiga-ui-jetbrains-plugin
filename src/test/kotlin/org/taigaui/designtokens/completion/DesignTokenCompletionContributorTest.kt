package org.taigaui.designtokens.completion

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.util.ui.UIUtil
import org.taigaui.designtokens.documentation.DesignTokenHoverPopupModel
import org.taigaui.designtokens.project.DesignTokenIndexService
import org.taigaui.designtokens.settings.TaigaDesignTokensSettings
import java.nio.file.Files
import java.nio.file.Path

class DesignTokenCompletionContributorTest : BasePlatformTestCase() {
    private lateinit var tempRoot: Path
    private lateinit var workspaceRoot: Path
    private lateinit var indexService: DesignTokenIndexService

    override fun setUp() {
        super.setUp()
        tempRoot = Files.createTempDirectory("design-token-completion")
        workspaceRoot = tempRoot.resolve("workspace")
        indexService = project.getService(DesignTokenIndexService::class.java)
        indexService.clear()
        myFixture.enableInspections(UnknownDesignTokenInspection())

        createFile(workspaceRoot.resolve("package.json"), "{}")
        createFile(
            workspaceRoot.resolve("node_modules/@taiga-ui/design-tokens/package.json"),
            """{"name":"@taiga-ui/design-tokens","version":"0.310.0"}""",
        )
        createFile(
            workspaceRoot.resolve("node_modules/@taiga-ui/design-tokens/tokens.css"),
            """
            :root {
                --tui-text-primary: #000;
                --tui-text-secondary: #666;
                --tui-background-base: #fff;
                --tui-background-elevation-1: #fff;
                --tui-background-elevation-2: #fff;
                --tui-border-hover: #ccc;
                --tui-border-normal: #ddd;
                --tui-font-text: 16px;
                --tui-font-text-s: 14px;
                --tui-font-text-xs: 12px;
            }
            """.trimIndent(),
        )
        createFile(
            workspaceRoot.resolve("project.json"),
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
        createFile(
            workspaceRoot.resolve("src/styles.less"),
            """
            :root {
                --tui-team-color: hotpink;
                --tui-radius.%: 1rem;
            }
            """.trimIndent(),
        )
    }

    override fun tearDown() {
        try {
            indexService.clear()
            tempRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testCompletesInstalledAndProjectTokensByTypedPrefix() {
        val suggestions = complete("--tui-te")

        assertContainsElements(
            suggestions,
            "--tui-team-color",
            "--tui-text-primary",
        )
        assertFalse(suggestions.contains("--tui-background-base"))
    }

    fun testDoesNotCompleteMalformedProjectTokenName() {
        val suggestions = complete("--tui-ra")

        assertFalse(suggestions.contains("--tui-radius.%"))
    }

    fun testCompletionPreviewControllerBuildsPreviewForActiveLookup() {
        val sourcePath = configureCompletion("--tui-")

        indexService.completionTokenNames(sourcePath)
        val variants = requireNotNull(myFixture.completeBasic())

        assertTrue(variants.size > 1)

        val controller = project.service<DesignTokenCompletionPreviewController>()
        val lookup =
            requireNotNull(
                runInEdtAndGet { LookupManager.getActiveLookup(myFixture.editor) },
            )

        invokePrivate(controller, "requestPreview", lookup)

        assertNotNull(waitForPrivateField(controller, "previewKey"))

        val panel =
            requireNotNull(
                waitForPrivateField(controller, "previewPanel"),
            ) as DesignTokenCompletionPreviewPanel

        waitUntil {
            panel.accessibleContext
                ?.accessibleDescription
                ?.startsWith("Resolved values for ") == true
        }
    }

    fun testCompletionPreviewControllerAttachDetachAndDisabledSetting() {
        val sourcePath = configureCompletion("--tui-")

        indexService.completionTokenNames(sourcePath)
        val variants = requireNotNull(myFixture.completeBasic())

        assertTrue(variants.size > 1)

        val controller = project.service<DesignTokenCompletionPreviewController>()
        val lookup =
            requireNotNull(
                runInEdtAndGet { LookupManager.getActiveLookup(myFixture.editor) },
            )

        invokePrivate(controller, "attach", lookup)
        assertSame(lookup, waitForPrivateField(controller, "activeLookup"))
        assertNotNull(waitForPrivateField(controller, "activeListener"))

        invokePrivate(controller, "attach", lookup)
        assertSame(lookup, waitForPrivateField(controller, "activeLookup"))

        invokePrivate(controller, "detach")
        assertNull(readPrivateField(controller, "activeLookup"))
        assertNull(readPrivateField(controller, "activeListener"))
        assertNull(readPrivateField(controller, "previewKey"))

        val settings = service<TaigaDesignTokensSettings>()

        settings.showCompletionPreview = false

        try {
            invokePrivate(controller, "attach", lookup)

            assertNull(readPrivateField(controller, "activeLookup"))
            assertNull(readPrivateField(controller, "activeListener"))
        } finally {
            settings.showCompletionPreview = true
        }
    }

    fun testCompletionPreviewControllerRendersModelIntoExistingPanel() {
        val sourcePath = configureCompletion("--tui-")

        indexService.completionTokenNames(sourcePath)
        requireNotNull(myFixture.completeBasic())

        val controller = project.service<DesignTokenCompletionPreviewController>()
        val lookup =
            requireNotNull(
                runInEdtAndGet { LookupManager.getActiveLookup(myFixture.editor) },
            )

        invokePrivate(controller, "attach", lookup)
        assertNotNull(waitForPrivateField(controller, "previewPanel"))

        invokePrivate(
            controller,
            "showModel",
            lookup,
            DesignTokenHoverPopupModel.notFound(
                tokenName = "--tui-missing",
                suggestions = listOf("--tui-text-primary"),
            ),
        )

        assertNotNull(readPrivateField(controller, "previewPanel"))

        invokePrivate(controller, "clearPreviewRequest")
        assertNull(readPrivateField(controller, "previewKey"))
        assertNull(readPrivateField(controller, "previewJob"))
    }

    fun testCompletionPreviewControllerEnsuresAttachmentAndClearsInvalidRequests() {
        val sourcePath = configureCompletion("--tui-")

        indexService.completionTokenNames(sourcePath)
        requireNotNull(myFixture.completeBasic())

        val controller = project.service<DesignTokenCompletionPreviewController>()
        val lookup =
            requireNotNull(
                runInEdtAndGet { LookupManager.getActiveLookup(myFixture.editor) },
            )

        controller.ensureAttached()
        waitUntil { readPrivateField(controller, "activeLookup") === lookup }

        invokePrivate(controller, "requestPreview", lookup)
        assertNotNull(waitForPrivateField(controller, "previewKey"))

        // Repeating the same request must reuse the in-flight preview.
        invokePrivate(controller, "requestPreview", lookup)
        assertNotNull(readPrivateField(controller, "previewKey"))

        val settings = service<TaigaDesignTokensSettings>()

        settings.showCompletionPreview = false

        try {
            invokePrivate(controller, "requestPreview", lookup)

            assertNull(readPrivateField(controller, "previewKey"))
            assertNull(readPrivateField(controller, "previewJob"))
        } finally {
            settings.showCompletionPreview = true
        }

        runInEdtAndGet {
            myFixture.editor.caretModel.moveToOffset(0)
        }
        invokePrivate(controller, "requestPreview", lookup)

        waitUntil {
            readPrivateField(controller, "previewKey") == null &&
                readPrivateField(controller, "previewJob") == null
        }
    }

    fun testCompletesSingleInstalledTokenMatch() {
        val tokenPrefix = "--tui-text-prima"
        val expectedToken = "--tui-text-primary"
        val sourcePath = configureCompletion(tokenPrefix)

        indexService.completionTokenNames(sourcePath)
        val variants = myFixture.completeBasic()

        if (variants == null) {
            assertEquals(
                ".demo { color: var($expectedToken); }",
                myFixture.editor.document.text,
            )
        } else {
            assertContainsElements(
                variants.map { variant -> variant.lookupString },
                expectedToken,
            )
        }
    }

    fun testHighlightsOnlyUnknownTaigaToken() {
        val sourcePath = workspaceRoot.resolve("src/inspection.less")
        val sourceFile =
            createFile(
                sourcePath,
                """
                .demo {
                    color: var(--tui-text-primary);
                    background: var(--tui-team-color);
                    border-color: var(--tui-text-primari);
                }
                """.trimIndent(),
            )

        myFixture.configureFromExistingVirtualFile(sourceFile)
        indexService.completionTokenNames(sourcePath)

        val problems =
            myFixture
                .doHighlighting()
                .filter { info -> info.description == UNKNOWN_TOKEN_MESSAGE }

        assertEquals(1, problems.size)
        val problem = problems.single()
        val highlightedText =
            myFixture.editor.document.charsSequence
                .subSequence(problem.startOffset, problem.endOffset)
                .toString()

        assertEquals("--tui-text-primari", highlightedText)
    }

    fun testReplacesUnknownTokenWithClosestKnownToken() {
        val unknownToken = "--tui-text-primari"
        val sourcePath = workspaceRoot.resolve("src/quick-fix.less")
        val sourceFile =
            createFile(
                sourcePath,
                ".demo { color: var($unknownToken); }",
            )

        myFixture.configureFromExistingVirtualFile(sourceFile)
        val tokenOffset =
            myFixture.editor.document.text
                .indexOf(unknownToken)

        myFixture.editor.caretModel.moveToOffset(tokenOffset + unknownToken.length / 2)
        indexService.completionTokenNames(sourcePath)
        myFixture.doHighlighting()

        val quickFix = myFixture.findSingleIntention("Replace with --tui-text-primary")

        myFixture.launchAction(quickFix)
        assertEquals(
            ".demo { color: var(--tui-text-primary); }",
            myFixture.editor.document.text,
        )
    }

    fun testOffersPrefixQuickFixesForIncompleteToken() {
        val unknownToken = "--tui-border"
        val sourcePath = workspaceRoot.resolve("src/prefix-quick-fix.less")
        val sourceFile =
            createFile(
                sourcePath,
                ".demo { border-color: var($unknownToken); }",
            )

        myFixture.configureFromExistingVirtualFile(sourceFile)
        val tokenOffset =
            myFixture.editor.document.text
                .indexOf(unknownToken)

        myFixture.editor.caretModel.moveToOffset(tokenOffset + unknownToken.length / 2)
        indexService.completionTokenNames(sourcePath)
        myFixture.doHighlighting()

        val quickFixNames =
            myFixture
                .filterAvailableIntentions("Replace with --tui-border")
                .map { action -> action.text }

        assertContainsElements(
            quickFixNames,
            "Replace with --tui-border-hover",
            "Replace with --tui-border-normal",
        )

        val quickFix = myFixture.findSingleIntention("Replace with --tui-border-normal")

        myFixture.launchAction(quickFix)
        assertEquals(
            ".demo { border-color: var(--tui-border-normal); }",
            myFixture.editor.document.text,
        )
    }

    fun testOffersQuickFixesWhileLastTokenSegmentIsPartial() {
        val unknownToken = "--tui-font-te"
        val sourcePath = workspaceRoot.resolve("src/partial-prefix-quick-fix.less")
        val sourceFile =
            createFile(
                sourcePath,
                ".demo { font: var($unknownToken); }",
            )

        myFixture.configureFromExistingVirtualFile(sourceFile)
        val tokenOffset =
            myFixture.editor.document.text
                .indexOf(unknownToken)

        myFixture.editor.caretModel.moveToOffset(tokenOffset + unknownToken.length / 2)
        indexService.completionTokenNames(sourcePath)
        myFixture.doHighlighting()

        val quickFixNames =
            myFixture
                .filterAvailableIntentions("Replace with --tui-font-text")
                .map { action -> action.text }

        assertContainsElements(
            quickFixNames,
            "Replace with --tui-font-text",
            "Replace with --tui-font-text-s",
            "Replace with --tui-font-text-xs",
        )
    }

    fun testOffersClosestQuickFixForMistypedTokenSuffix() {
        val unknownToken = "--tui-font-text-xs2"
        val sourcePath = workspaceRoot.resolve("src/typo-suffix-quick-fix.less")
        val sourceFile =
            createFile(
                sourcePath,
                ".demo { font: var($unknownToken); }",
            )

        myFixture.configureFromExistingVirtualFile(sourceFile)
        val tokenOffset =
            myFixture.editor.document.text
                .indexOf(unknownToken)

        myFixture.editor.caretModel.moveToOffset(tokenOffset + unknownToken.length / 2)
        indexService.completionTokenNames(sourcePath)
        myFixture.doHighlighting()

        val quickFix = myFixture.findSingleIntention("Replace with --tui-font-text-xs")

        myFixture.launchAction(quickFix)
        assertEquals(
            ".demo { font: var(--tui-font-text-xs); }",
            myFixture.editor.document.text,
        )
    }

    fun testOffersCoreTypoQuickFixWhenProprietaryDoesNotImportCoreVariables() {
        val proprietaryWorkspace = tempRoot.resolve("proprietary-workspace")
        val scopeRoot = proprietaryWorkspace.resolve("node_modules/@taiga-ui")
        val unknownToken = "--tui-font-text-xs2"
        val sourcePath = proprietaryWorkspace.resolve("src/component.less")
        val sourceFile =
            createFile(
                sourcePath,
                ".demo { font: var($unknownToken); }",
            )

        createFile(
            scopeRoot.resolve("core/package.json"),
            """
            {
                "name": "@taiga-ui/core",
                "version": "4.21.0",
                "exports": {"./styles/*": "./styles/*"}
            }
            """.trimIndent(),
        )
        createFile(
            scopeRoot.resolve("core/styles/theme/variables.less"),
            ":root { --tui-font-text-xs: normal 0.6875rem/1rem sans-serif; }",
        )
        createFile(
            scopeRoot.resolve("proprietary/package.json"),
            """
            {
                "name": "@taiga-ui/proprietary",
                "version": "4.21.0",
                "exports": {"./styles/*": "./styles/*"}
            }
            """.trimIndent(),
        )
        createFile(
            scopeRoot.resolve("proprietary/styles/theme.less"),
            ":root { --tui-proprietary-only: red; }",
        )

        myFixture.configureFromExistingVirtualFile(sourceFile)
        val tokenOffset =
            myFixture.editor.document.text
                .indexOf(unknownToken)

        myFixture.editor.caretModel.moveToOffset(tokenOffset + unknownToken.length / 2)
        indexService.completionTokenNames(sourcePath)
        myFixture.doHighlighting()

        val quickFix = myFixture.findSingleIntention("Replace with --tui-font-text-xs")

        myFixture.launchAction(quickFix)
        assertEquals(
            ".demo { font: var(--tui-font-text-xs); }",
            myFixture.editor.document.text,
        )
    }

    private fun complete(tokenPrefix: String): List<String> {
        val sourcePath = configureCompletion(tokenPrefix)

        indexService.completionTokenNames(sourcePath)
        myFixture.completeBasic()

        return myFixture.lookupElementStrings.orEmpty()
    }

    private fun configureCompletion(tokenPrefix: String): Path {
        val sourcePath = workspaceRoot.resolve("src/component.less")
        val sourceFile =
            createFile(
                sourcePath,
                ".demo { color: var($tokenPrefix); }",
            )

        myFixture.configureFromExistingVirtualFile(sourceFile)
        val text = myFixture.editor.document.text
        val caretOffset = text.indexOf(tokenPrefix) + tokenPrefix.length

        myFixture.editor.caretModel.moveToOffset(caretOffset)

        return sourcePath
    }

    private fun invokePrivate(
        target: Any,
        methodName: String,
        vararg arguments: Any?,
    ) {
        val method =
            target.javaClass.declaredMethods
                .single { candidate ->
                    candidate.name == methodName &&
                        candidate.parameterCount == arguments.size
                }.apply { isAccessible = true }

        runInEdtAndGet { method.invoke(target, *arguments) }
    }

    private fun readPrivateField(
        target: Any,
        fieldName: String,
    ): Any? {
        val field =
            target.javaClass
                .getDeclaredField(fieldName)
                .apply { isAccessible = true }

        return runInEdtAndGet { field.get(target) }
    }

    private fun waitForPrivateField(
        target: Any,
        fieldName: String,
    ): Any? {
        val field =
            target.javaClass
                .getDeclaredField(fieldName)
                .apply { isAccessible = true }

        repeat(200) {
            val value = runInEdtAndGet { field.get(target) }

            if (value != null) {
                return value
            }

            Thread.sleep(10)
        }

        return runInEdtAndGet { field.get(target) }
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(500) {
            UIUtil.dispatchAllInvocationEvents()

            if (condition()) {
                return
            }

            Thread.sleep(10)
        }

        assertTrue(condition())
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

    private companion object {
        const val UNKNOWN_TOKEN_MESSAGE = "Unknown Taiga UI design token"
    }
}
