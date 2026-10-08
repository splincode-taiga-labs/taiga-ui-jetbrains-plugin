package org.taigaui.designtokens.icons

import com.intellij.codeInsight.lookup.LookupArranger
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.util.ui.UIUtil
import org.taigaui.designtokens.visiblePopupStub
import java.awt.Point
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.ImageIcon

class IconHoverPopupControllerTest : BasePlatformTestCase() {
    private lateinit var tempRoot: Path

    override fun setUp() {
        super.setUp()
        tempRoot = Files.createTempDirectory("icon-hover-controller")
    }

    override fun tearDown() {
        try {
            tempRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testValidIconHoverStartsRequestAndDismissRestoresState() {
        configureHtml("""<button iconStart="@tui.search"></button>""")
        val editor = myFixture.editor
        val controller = project.service<IconHoverPopupController>()
        val offset = editor.document.text.indexOf("@tui.search") + 3

        controller.mouseMoved(editorMouseEvent(offset))

        assertNotNull(waitForPrivateField(controller, "activeKey"))
        assertNotNull(readPrivateField(controller, "hoverJob"))
        assertSame(editor, readPrivateField(controller, "nativeHoverSuppressedEditor"))

        controller.dismissHover(editor)

        assertNull(readPrivateField(controller, "activeKey"))
        assertNull(readPrivateField(controller, "hoverJob"))
        assertNull(readPrivateField(controller, "nativeHoverSuppressedEditor"))
    }

    fun testMissingIconClearsHoverAfterAsyncResolution() {
        configureHtml("""<button iconStart="@tui.search"></button>""")
        val editor = myFixture.editor
        val controller = project.service<IconHoverPopupController>()
        val offset = editor.document.text.indexOf("@tui.search") + 3

        controller.mouseMoved(editorMouseEvent(offset))

        assertNotNull(waitForPrivateField(controller, "activeKey"))
        waitUntilNull(controller, "activeKey")

        assertNull(readPrivateField(controller, "hoverJob"))
        assertNull(readPrivateField(controller, "popup"))
    }

    fun testInstalledIconHoverShowsPopupAfterAsyncResolution() {
        val workspace = tempRoot.resolve("workspace")
        val icon =
            workspace.resolve(
                "node_modules/@taiga-ui/icons/src/search.svg",
            )

        Files.createDirectories(icon.parent)
        Files.writeString(
            icon,
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M4 12h16"/></svg>""",
        )
        configureSource(
            fileName = "workspace/src/icons.html",
            content = """<button iconStart="@tui.search"></button>""",
        )

        val editor = myFixture.editor
        val controller = project.service<IconHoverPopupController>()
        val offset = editor.document.text.indexOf("@tui.search") + 3

        controller.mouseMoved(editorMouseEvent(offset))

        val popup = requireNotNull(waitForPrivateField(controller, "popup")) as com.intellij.openapi.ui.popup.JBPopup

        runInEdtAndGet { popup.cancel() }
        waitUntilNull(controller, "popup")

        assertNull(readPrivateField(controller, "activeKey"))
        assertNull(readPrivateField(controller, "hoverJob"))
    }

    fun testDocumentChangeMakesDelayedHoverStale() {
        configureHtml("""<button iconStart="@tui.search"></button>""")
        val editor = myFixture.editor
        val controller = project.service<IconHoverPopupController>()
        val offset = editor.document.text.indexOf("@tui.search") + 3

        controller.mouseMoved(editorMouseEvent(offset))
        assertNotNull(waitForPrivateField(controller, "activeKey"))

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(0, " ")
        }

        waitUntilNull(controller, "activeKey")

        assertNull(readPrivateField(controller, "hoverJob"))
    }

    fun testVisiblePopupRejectsDuplicateShowRequest() {
        val workspace = tempRoot.resolve("duplicate-workspace")
        val sourcePath = workspace.resolve("src/icons.html")
        val iconPath = workspace.resolve("node_modules/@taiga-ui/icons/src/search.svg")

        Files.createDirectories(iconPath.parent)
        Files.writeString(
            iconPath,
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M4 12h16"/></svg>""",
        )
        configureSource(
            fileName = "duplicate-workspace/src/icons.html",
            content = """<button iconStart="@tui.search"></button>""",
        )

        val editor = myFixture.editor
        val controller = project.service<IconHoverPopupController>()
        val offset = editor.document.text.indexOf("@tui.search") + 3

        controller.mouseMoved(editorMouseEvent(offset))
        val popup = requireNotNull(waitForPrivateField(controller, "popup")) as com.intellij.openapi.ui.popup.JBPopup
        val visiblePopup = visiblePopupStub()
        val popupField = controller.javaClass.getDeclaredField("popup").apply { isAccessible = true }
        val reference = requireNotNull(IconReferenceAtOffsetFinder.find(editor.document.text, offset))
        val requestClass = Class.forName("org.taigaui.designtokens.icons.IconHoverRequest")
        val request =
            requestClass.declaredConstructors
                .single()
                .apply { isAccessible = true }
                .newInstance(
                    editor,
                    sourcePath,
                    reference,
                    Point(0, 0),
                    editor.document.modificationStamp,
                )
        val showPopup =
            controller.javaClass.declaredMethods
                .single { method ->
                    method.name == "showPopup" && method.parameterCount == 2
                }.apply { isAccessible = true }

        runInEdtAndGet {
            popup.cancel()
            popupField.set(controller, visiblePopup)
            showPopup.invoke(
                controller,
                request,
                ImageIcon(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)),
            )
            assertSame(visiblePopup, popupField.get(controller))
        }
        controller.dismissHover(editor)
    }

    fun testListenerDismissesHoverWhileIconLookupIsActive() {
        val workspace = tempRoot.resolve("lookup-workspace")
        val sourcePath = workspace.resolve("src/icons.html")
        val iconPath = workspace.resolve("node_modules/@taiga-ui/icons/src/search.svg")

        Files.createDirectories(iconPath.parent)
        Files.writeString(
            iconPath,
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M4 12h16"/></svg>""",
        )
        configureSource(
            fileName = "lookup-workspace/src/icons.html",
            content = """<button iconStart="@tui.search"></button>""",
        )

        val controller = project.service<IconHoverPopupController>()
        val listener = IconHoverPopupListener()
        val offset =
            myFixture.editor.document.text
                .indexOf("@tui.search") + 3
        val event = editorMouseEvent(offset)

        listener.mouseMoved(event)
        assertNotNull(readPrivateField(controller, "activeKey"))

        val lookup =
            runInEdtAndGet {
                LookupManager.getInstance(project).showLookup(
                    myFixture.editor,
                    arrayOf(
                        LookupElementBuilder.create("@tui.search"),
                        LookupElementBuilder.create("@tui.add"),
                    ),
                    "@tui.",
                    object : LookupArranger.DefaultArranger() {
                        override fun isCompletion(): Boolean = true
                    },
                )
            }

        assertNotNull(lookup)

        listener.mouseMoved(event)

        assertNull(readPrivateField(controller, "activeKey"))
        runInEdtAndGet { LookupManager.getInstance(project).hideActiveLookup() }
    }

    fun testRepeatedSameHoverKeepsSingleActiveKey() {
        configureHtml("""<button iconStart="@tui.search"></button>""")
        val editor = myFixture.editor
        val controller = project.service<IconHoverPopupController>()
        val offset = editor.document.text.indexOf("@tui.search") + 3

        controller.mouseMoved(editorMouseEvent(offset))
        val key = requireNotNull(waitForPrivateField(controller, "activeKey"))
        val job = requireNotNull(readPrivateField(controller, "hoverJob"))

        controller.mouseMoved(editorMouseEvent(offset))

        assertSame(key, readPrivateField(controller, "activeKey"))
        assertSame(job, readPrivateField(controller, "hoverJob"))

        controller.dismissHover()
    }

    fun testNonEditingAreaAndSelectionDoNotStartIconHover() {
        configureHtml("""<button iconStart="@tui.search"></button>""")
        val editor = myFixture.editor
        val controller = project.service<IconHoverPopupController>()
        val offset = editor.document.text.indexOf("@tui.search") + 3

        controller.mouseMoved(
            editorMouseEvent(
                offset,
                EditorMouseEventArea.LINE_NUMBERS_AREA,
            ),
        )
        assertNull(readPrivateField(controller, "activeKey"))

        editor.selectionModel.setSelection(0, 1)
        controller.mouseMoved(editorMouseEvent(offset))

        assertNull(readPrivateField(controller, "activeKey"))
    }

    fun testUnsupportedSourceExtensionDoesNotStartIconHover() {
        configureSource(
            fileName = "icons.css",
            content = """.demo { content: "@tui.search"; }""",
        )
        val controller = project.service<IconHoverPopupController>()
        val offset =
            myFixture.editor.document.text
                .indexOf("@tui.search") + 3

        controller.mouseMoved(editorMouseEvent(offset))

        assertNull(readPrivateField(controller, "activeKey"))
    }

    fun testListenerForwardsMovePressAndDragToIconHoverController() {
        configureHtml("""<button iconStart="@tui.search"></button>""")
        val controller = project.service<IconHoverPopupController>()
        val listener = IconHoverPopupListener()
        val offset =
            myFixture.editor.document.text
                .indexOf("@tui.search") + 3
        val event = editorMouseEvent(offset)

        listener.mouseMoved(event)
        assertNotNull(readPrivateField(controller, "activeKey"))

        listener.mousePressed(event)
        assertNull(readPrivateField(controller, "activeKey"))

        listener.mouseMoved(event)
        assertNotNull(readPrivateField(controller, "activeKey"))

        listener.mouseDragged(event)
        assertNull(readPrivateField(controller, "activeKey"))
    }

    private fun configureHtml(content: String) {
        configureSource("icons.html", content)
    }

    private fun configureSource(
        fileName: String,
        content: String,
    ) {
        val path = tempRoot.resolve(fileName)

        Files.createDirectories(path.parent)
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
        repeat(500) {
            UIUtil.dispatchAllInvocationEvents()
            readPrivateField(target, fieldName)?.let { return it }
            Thread.sleep(10)
        }

        return readPrivateField(target, fieldName)
    }

    private fun waitUntilNull(
        target: Any,
        fieldName: String,
    ) {
        repeat(500) {
            UIUtil.dispatchAllInvocationEvents()

            if (readPrivateField(target, fieldName) == null) {
                return
            }

            Thread.sleep(10)
        }

        assertNull(readPrivateField(target, fieldName))
    }
}
