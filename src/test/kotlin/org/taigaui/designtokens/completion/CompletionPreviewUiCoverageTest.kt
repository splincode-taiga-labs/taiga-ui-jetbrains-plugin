package org.taigaui.designtokens.completion

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.ui.LightweightHint
import org.taigaui.designtokens.documentation.DesignTokenHoverPopupModel
import org.taigaui.designtokens.project.DesignTokenIndexService
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
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

        withEditorRootPane {
            showPreview(controller, lookup)
            val hint = requireNotNull(readPrivateField(controller, "previewHint")) as LightweightHint

            assertTrue(hint.isVisible)

            showPreview(controller, lookup)
            assertTrue(hint.isVisible)

            runInEdtAndGet { lookup.hideLookup(true) }

            assertFalse(hint.isVisible)
        }
    }

    fun testPreviewHidesHintWhenLookupItemIsSelected() {
        val lookup = openLookup()
        val controller = project.service<DesignTokenCompletionPreviewController>()

        invokePrivate(controller, "attach", lookup)

        withEditorRootPane {
            showPreview(controller, lookup)
            val hint = requireNotNull(readPrivateField(controller, "previewHint")) as LightweightHint

            assertTrue(hint.isVisible)

            myFixture.finishLookup('\n')

            assertFalse(hint.isVisible)
        }
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
        val caretOffset = myFixture.editor.document.text.indexOf(prefix) + prefix.length

        myFixture.editor.caretModel.moveToOffset(caretOffset)
        indexService.completionTokenNames(sourcePath)

        val variants = requireNotNull(myFixture.completeBasic())

        assertTrue(variants.size > 1)

        return requireNotNull(
            runInEdtAndGet { LookupManager.getActiveLookup(myFixture.editor) },
        )
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

    private fun withEditorRootPane(block: () -> Unit) {
        val component = myFixture.editor.component
        val rootPane = JRootPane()

        runInEdtAndGet {
            component.parent?.remove(component)
            rootPane.contentPane.layout = BorderLayout()
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
