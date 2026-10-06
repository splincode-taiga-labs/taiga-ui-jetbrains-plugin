package org.taigaui.designtokens.icons

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.ui.LightweightHint
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.ImageIcon
import javax.swing.JLayeredPane
import javax.swing.JRootPane

class IconCompletionPreviewUiCoverageTest : BasePlatformTestCase() {
    private lateinit var workspaceRoot: Path

    override fun setUp() {
        super.setUp()
        workspaceRoot = Files.createTempDirectory("icon-preview-ui")
        createIcon("icons/src/a-arrow-down.svg")
        createIcon("icons/src/a-arrow-up.svg")
    }

    override fun tearDown() {
        try {
            project.service<IconCompletionService>().clear()
            workspaceRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testPreviewCreatesMovesAndHidesHintWhenLookupIsCanceled() {
        val lookup = openLookup()
        val controller = project.service<IconCompletionPreviewController>()

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
        val controller = project.service<IconCompletionPreviewController>()

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

    private fun openLookup(): Lookup {
        val sourcePath = workspaceRoot.resolve("src/icons.html")
        val file =
            createFile(
                sourcePath,
                "<button iconStart=\"@tui.\"></button>",
            )

        myFixture.configureFromExistingVirtualFile(file)
        val prefix = "@tui."
        val caretOffset =
            myFixture.editor.document.text
                .indexOf(prefix) + prefix.length

        myFixture.editor.caretModel.moveToOffset(caretOffset)
        project.service<IconCompletionService>().loadNow(sourcePath)

        val variants = requireNotNull(myFixture.completeBasic())

        assertTrue(variants.size > 1)

        return requireNotNull(
            runInEdtAndGet { LookupManager.getActiveLookup(myFixture.editor) },
        )
    }

    private fun showPreview(
        controller: IconCompletionPreviewController,
        lookup: Lookup,
    ) {
        invokePrivate(
            controller,
            "showIcon",
            lookup,
            requireNotNull(lookup.currentItem).lookupString,
            ImageIcon(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)),
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

    private fun createIcon(relativePath: String) {
        createFile(
            workspaceRoot.resolve("node_modules/@taiga-ui/$relativePath"),
            "<svg viewBox=\"0 0 24 24\"><path d=\"M4 12h16\"/></svg>",
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
}
