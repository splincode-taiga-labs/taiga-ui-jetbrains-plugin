package org.taigaui.designtokens.completion

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupArranger
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.codeInsight.lookup.LookupListener
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.ui.LightweightHint
import com.intellij.util.ui.UIUtil
import org.taigaui.designtokens.documentation.DesignTokenHoverPopupModel
import org.taigaui.designtokens.project.DesignTokenIndexService
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JLayeredPane
import javax.swing.JRootPane

class CompletionPreviewUiCoverageTest : BasePlatformTestCase() {
    private lateinit var workspaceRoot: Path
    private lateinit var indexService: DesignTokenIndexService

    override fun setUp() {
        super.setUp()
        workspaceRoot = Files.createTempDirectory("completion-preview-ui")
        indexService = project.service()
        indexService.clear()

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
            }
            """.trimIndent(),
        )
    }

    override fun tearDown() {
        try {
            indexService.clear()
            workspaceRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testPreviewCreatesMovesAndHidesHintWhenLookupIsCanceled() {
        val lookup = openLookup()
        val controller = project.service<DesignTokenCompletionPreviewController>()

        invokePrivate(controller, "attach", lookup)

        withEditorRootPane(lookup) {
            showPreview(controller, lookup)
            val hint = requireNotNull(readPrivateField(controller, "previewHint")) as LightweightHint

            assertSame(hint, readPrivateField(controller, "previewHint"))

            showPreview(controller, lookup)
            assertSame(hint, readPrivateField(controller, "previewHint"))

            runInEdtAndGet { LookupManager.getInstance(project).hideActiveLookup() }

            UIUtil.dispatchAllInvocationEvents()
            assertSame(hint, readPrivateField(controller, "previewHint"))
        }
    }

    fun testPreviewHidesHintWhenLookupItemIsSelected() {
        val lookup = openLookup()
        val controller = project.service<DesignTokenCompletionPreviewController>()

        invokePrivate(controller, "attach", lookup)

        withEditorRootPane(lookup) {
            showPreview(controller, lookup)
            val hint = requireNotNull(readPrivateField(controller, "previewHint")) as LightweightHint

            assertSame(hint, readPrivateField(controller, "previewHint"))

            myFixture.finishLookup('\n')

            UIUtil.dispatchAllInvocationEvents()
            assertSame(hint, readPrivateField(controller, "previewHint"))
        }
    }

    fun testAttachedListenerHandlesSelectionAndCancellationCallbacksDirectly() {
        val lookup = openLookup()
        val controller = project.service<DesignTokenCompletionPreviewController>()

        invokePrivate(controller, "attach", lookup)

        val listener = requireNotNull(readPrivateField(controller, "activeListener")) as LookupListener

        listener.itemSelected(
            LookupEvent(
                lookup,
                lookup.currentItem,
                '\n',
            ),
        )
        listener.lookupCanceled(LookupEvent(lookup, true))

        assertSame(listener, readPrivateField(controller, "activeListener"))
    }

    fun testPreviewFallsBackToLinkedCustomPropertyWhenIndexHasNoToken() {
        val usage =
            createFile(
                workspaceRoot.resolve("src/fallback.less"),
                ".demo { color: var(--tui-linked); }",
            )
        val declaration =
            myFixture.addFileToProject(
                "linked.css",
                ":root { --tui-linked: hotpink; }",
            )
        myFixture.configureFromExistingVirtualFile(usage)
        val caretOffset =
            myFixture.editor.document.text
                .indexOf("--tui-linked") + "--tui-linked".length
        myFixture.editor.caretModel.moveToOffset(caretOffset)
        val declarationOffset = declaration.text.indexOf("--tui-linked")
        val element = requireNotNull(declaration.findElementAt(declarationOffset))
        val item = LookupElementBuilder.create(element, "--tui-linked")
        val lookup = showLookup(listOf(item), "--tui-linked")
        val controller = project.service<DesignTokenCompletionPreviewController>()

        invokePrivate(controller, "attach", lookup)
        waitForPreviewJob(controller)

        assertNotNull(readPrivateField(controller, "previewPanel"))
        runInEdtAndGet { LookupManager.getInstance(project).hideActiveLookup() }
    }

    fun testPreviewHidesWhenIndexAndCustomPropertyFallbackAreEmpty() {
        val usage =
            createFile(
                workspaceRoot.resolve("src/missing.less"),
                ".demo { color: var(--tui-missing-preview); }",
            )
        myFixture.configureFromExistingVirtualFile(usage)
        val caretOffset =
            myFixture.editor.document.text
                .indexOf("--tui-missing-preview") + "--tui-missing-preview".length
        myFixture.editor.caretModel.moveToOffset(caretOffset)
        val item = LookupElementBuilder.create("--tui-missing-preview")
        val lookup = showLookup(listOf(item), "--tui-missing-preview")
        val controller = project.service<DesignTokenCompletionPreviewController>()

        invokePrivate(controller, "attach", lookup)
        waitForPreviewJob(controller)

        assertFalse((readPrivateField(controller, "previewHint") as? LightweightHint)?.isVisible == true)
        runInEdtAndGet { LookupManager.getInstance(project).hideActiveLookup() }
    }

    fun testPreviewRequestClearsWhenCaretLeavesDesignTokenContext() {
        val lookup = openLookup()
        val controller = project.service<DesignTokenCompletionPreviewController>()

        invokePrivate(controller, "attach", lookup)

        myFixture.editor.caretModel.moveToOffset(0)
        invokePrivate(controller, "requestPreview", lookup)
        waitForPreviewJob(controller)

        assertNull(readPrivateField(controller, "previewKey"))
        runInEdtAndGet { LookupManager.getInstance(project).hideActiveLookup() }
    }

    private fun openLookup(): Lookup {
        val sourcePath = workspaceRoot.resolve("src/component.less")
        val file =
            createFile(
                sourcePath,
                ".demo { color: var(--tui-); }",
            )

        myFixture.configureFromExistingVirtualFile(file)
        val prefix = "--tui-"
        val caretOffset =
            myFixture.editor.document.text
                .indexOf(prefix) + prefix.length

        myFixture.editor.caretModel.moveToOffset(caretOffset)
        indexService.completionTokenNames(sourcePath)

        val variants = requireNotNull(myFixture.completeBasic())

        assertTrue(variants.size > 1)

        return requireNotNull(
            runInEdtAndGet { LookupManager.getActiveLookup(myFixture.editor) },
        )
    }

    private fun showLookup(
        items: List<LookupElement>,
        prefix: String,
    ): Lookup =
        requireNotNull(
            runInEdtAndGet {
                LookupManager
                    .getInstance(project)
                    .showLookup(
                        myFixture.editor,
                        items.toTypedArray(),
                        prefix,
                        object : LookupArranger.DefaultArranger() {
                            override fun isCompletion(): Boolean = true
                        },
                    )
            },
        )

    private fun waitForPreviewJob(controller: DesignTokenCompletionPreviewController) {
        repeat(300) {
            UIUtil.dispatchAllInvocationEvents()
            val job = readPrivateField(controller, "previewJob") as? kotlinx.coroutines.Job

            if (job == null || !job.isActive) {
                return
            }

            Thread.sleep(10)
        }

        fail("Completion preview job did not finish")
    }

    private fun showPreview(
        controller: DesignTokenCompletionPreviewController,
        lookup: Lookup,
    ) {
        invokePrivate(
            controller,
            "showModel",
            lookup,
            DesignTokenHoverPopupModel.notFound(
                tokenName = "--tui-missing",
                suggestions = listOf("--tui-text-primary"),
            ),
        )
    }

    private fun withEditorRootPane(
        lookup: Lookup,
        block: () -> Unit,
    ) {
        val component = lookup.topLevelEditor.contentComponent
        val rootPane = JRootPane()

        runInEdtAndGet {
            rootPane.layeredPane =
                object : JLayeredPane() {
                    override fun isShowing(): Boolean = true
                }
            rootPane.contentPane = javax.swing.JPanel(BorderLayout())
            component.parent?.remove(component)
            rootPane.contentPane.add(component, BorderLayout.CENTER)
            rootPane.setSize(1_200, 800)
            rootPane.layeredPane.setSize(1_200, 800)
            component.setSize(1_000, 700)
            rootPane.doLayout()
            component.doLayout()
        }

        try {
            block()
        } finally {
            runInEdtAndGet {
                rootPane.contentPane.remove(component)
            }
        }
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
    ): Any? =
        target.javaClass
            .getDeclaredField(fieldName)
            .apply { isAccessible = true }
            .let { field -> runInEdtAndGet { field.get(target) } }

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
}
