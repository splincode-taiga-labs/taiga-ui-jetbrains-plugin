package org.taigaui.designtokens.documentation

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.util.ui.UIUtil
import org.taigaui.designtokens.project.DesignTokenIndexService
import org.taigaui.designtokens.settings.TaigaDesignTokensSettings
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path

class DesignTokenHoverPopupControllerTest : BasePlatformTestCase() {
    private lateinit var tempRoot: Path

    override fun setUp() {
        super.setUp()
        tempRoot = Files.createTempDirectory("design-token-hover-controller")
        project.service<DesignTokenIndexService>().clear()
        service<TaigaDesignTokensSettings>().showHoverPopup = true
    }

    override fun tearDown() {
        try {
            project.service<DesignTokenIndexService>().clear()
            service<TaigaDesignTokensSettings>().showHoverPopup = true
            tempRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testDisabledSettingDismissesHoverImmediately() {
        configureCss(".demo { color: var(--tui-text-primary); }")
        val controller = project.service<DesignTokenHoverPopupController>()
        val offset =
            myFixture.editor.document.text
                .indexOf("--tui-text-primary") + 3

        service<TaigaDesignTokensSettings>().showHoverPopup = false
        controller.mouseMoved(editorMouseEvent(offset))

        assertNull(readPrivateField(controller, "activeHoverKey"))
        assertNull(readPrivateField(controller, "latestHoverRequest"))
    }

    fun testValidRequestBecomesStaleAfterDocumentChange() {
        configureCss(".demo { color: var(--tui-text-primary); }")
        val editor = myFixture.editor
        val controller = project.service<DesignTokenHoverPopupController>()
        val offset = editor.document.text.indexOf("--tui-text-primary") + 3

        controller.mouseMoved(editorMouseEvent(offset))

        assertNotNull(waitForPrivateField(controller, "activeHoverKey"))
        assertNotNull(readPrivateField(controller, "latestHoverRequest"))

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(0, " ")
        }
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)

        waitUntilNull(controller, "activeHoverKey")

        assertNull(readPrivateField(controller, "latestHoverRequest"))
        assertNull(readPrivateField(controller, "popup"))
    }

    fun testMovingAwayFromReferenceClearsPendingHover() {
        configureCss(".demo { color: var(--tui-text-primary); }")
        val editor = myFixture.editor
        val controller = project.service<DesignTokenHoverPopupController>()
        val offset = editor.document.text.indexOf("--tui-text-primary") + 3

        controller.mouseMoved(editorMouseEvent(offset))
        assertNotNull(waitForPrivateField(controller, "activeHoverKey"))

        controller.mouseMoved(editorMouseEvent(0))

        assertNull(readPrivateField(controller, "activeHoverKey"))
        assertNull(readPrivateField(controller, "latestHoverRequest"))
    }

    fun testSelectionBlocksHoverRequest() {
        configureCss(".demo { color: var(--tui-text-primary); }")
        val editor = myFixture.editor
        val controller = project.service<DesignTokenHoverPopupController>()
        val offset = editor.document.text.indexOf("--tui-text-primary") + 3

        editor.selectionModel.setSelection(0, 1)
        controller.mouseMoved(editorMouseEvent(offset))

        assertNull(readPrivateField(controller, "activeHoverKey"))
        assertNull(readPrivateField(controller, "latestHoverRequest"))
    }

    fun testNonEditingAreaDoesNotStartHover() {
        configureCss(".demo { color: var(--tui-text-primary); }")
        val controller = project.service<DesignTokenHoverPopupController>()
        val offset =
            myFixture.editor.document.text
                .indexOf("--tui-text-primary") + 3

        controller.mouseMoved(
            editorMouseEvent(
                offset,
                EditorMouseEventArea.LINE_NUMBERS_AREA,
            ),
        )

        assertNull(readPrivateField(controller, "activeHoverKey"))
    }

    fun testPopupWidthUsesPreferredFallbackForFixtureEditor() {
        configureCss(".demo { color: red; }")
        val method =
            Class
                .forName("org.taigaui.designtokens.documentation.DesignTokenHoverPopupControllerKt")
                .declaredMethods
                .single { candidate ->
                    candidate.name == "calculateDesignTokenPopupWidth" &&
                        candidate.parameterCount == 1
                }.apply { isAccessible = true }

        val width =
            runInEdtAndGet {
                method.invoke(null, myFixture.editor) as Int
            }

        assertTrue(width > 0)
        assertTrue(width <= 560)
    }

    private fun configureCss(content: String) {
        val path = tempRoot.resolve("component.css")
        Files.writeString(path, content)
        val file =
            requireNotNull(
                LocalFileSystem
                    .getInstance()
                    .refreshAndFindFileByNioFile(path),
            )

        myFixture.configureFromExistingVirtualFile(file)
    }

    private fun editorMouseEvent(
        offset: Int,
        area: EditorMouseEventArea = EditorMouseEventArea.EDITING_AREA,
    ): EditorMouseEvent {
        val editor = myFixture.editor
        val point =
            editor.offsetToXY(offset).apply {
                translate(1, editor.lineHeight / 2)
            }
        val mouseEvent =
            MouseEvent(
                editor.contentComponent,
                MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(),
                0,
                point.x,
                point.y,
                0,
                false,
            )

        return EditorMouseEvent(
            editor,
            mouseEvent,
            area,
            offset,
            editor.offsetToLogicalPosition(offset),
            editor.offsetToVisualPosition(offset),
            true,
            null,
            null,
            null,
        )
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
        repeat(200) {
            readPrivateField(target, fieldName)?.let { return it }
            Thread.sleep(10)
        }

        return readPrivateField(target, fieldName)
    }

    private fun waitUntilNull(
        target: Any,
        fieldName: String,
    ) {
        repeat(300) {
            UIUtil.dispatchAllInvocationEvents()

            if (readPrivateField(target, fieldName) == null) {
                return
            }

            Thread.sleep(10)
        }

        assertNull(readPrivateField(target, fieldName))
    }
}
