package org.taigaui.designtokens.documentation

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.util.ui.UIUtil
import org.taigaui.designtokens.project.DesignTokenIndexService
import org.taigaui.designtokens.visiblePopupStub
import org.taigaui.designtokens.settings.TaigaDesignTokensSettings
import java.awt.Container
import java.awt.GraphicsConfiguration
import java.awt.GraphicsDevice
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.geom.AffineTransform
import java.awt.image.ColorModel
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JLabel
import javax.swing.JPanel

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

    fun testResolvedTokenHoverShowsPopupAfterDelay() {
        configureCss(
            """
            :root {
                --tui-text-primary: #ff0000;
            }

            .demo {
                color: var(--tui-text-primary);
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor
        val controller = project.service<DesignTokenHoverPopupController>()
        val offset = editor.document.text.lastIndexOf("--tui-text-primary") + 3

        controller.mouseMoved(editorMouseEvent(offset))

        try {
            val panel =
                requireNotNull(
                    waitForPrivateField(controller, "popupContent"),
                ) as DesignTokenHoverPopupPanel

            assertNotNull(readPrivateField(controller, "popupKey"))
            assertTrue(panel.componentCount > 0)
        } finally {
            controller.dismissHover(editor)
        }
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

    fun testListenerForwardsMovePressAndDragToHoverController() {
        configureCss(".demo { color: var(--tui-text-primary); }")
        val controller = project.service<DesignTokenHoverPopupController>()
        val listener = DesignTokenHoverPopupListener()
        val offset =
            myFixture.editor.document.text
                .indexOf("--tui-text-primary") + 3
        val event = editorMouseEvent(offset)

        listener.mouseMoved(event)
        assertNotNull(readPrivateField(controller, "activeHoverKey"))

        listener.mousePressed(event)
        assertNull(readPrivateField(controller, "activeHoverKey"))

        listener.mouseMoved(event)
        assertNotNull(readPrivateField(controller, "activeHoverKey"))

        listener.mouseDragged(event)
        assertNull(readPrivateField(controller, "activeHoverKey"))
    }

    fun testPopupInteractionSupportCoversInactiveEditorAndPopupCreation() {
        configureCss(".demo { color: red; }")
        val editor = myFixture.editor

        assertFalse(project.hasActiveCompletionLookup())
        assertFalse(project.blocksDesignTokenPopup(editor))

        editor.selectionModel.setSelection(0, 1)
        assertTrue(project.blocksDesignTokenPopup(editor))
        editor.selectionModel.removeSelection()

        assertFalse((null as DesignTokenHoverPopupPanel?).containsPointer())

        val panel =
            DesignTokenHoverPopupPanel(
                popupWidth = 560,
                onNavigate = {},
                onReportBug = {},
                onPreferredSizeChanged = {},
            )

        assertFalse(panel.containsPointer())

        val popup = createDesignTokenPopup(project, panel)

        assertNotNull(popup)
        popup.cancel()
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

    fun testPendingHoverCanBeScheduledForHideWithoutReadingRealPointerPosition() {
        configureCss(".demo { color: var(--tui-text-primary); }")
        val editor = myFixture.editor
        val controller = project.service<DesignTokenHoverPopupController>()
        val offset = editor.document.text.indexOf("--tui-text-primary") + 3

        controller.mouseMoved(editorMouseEvent(offset))
        assertNotNull(waitForPrivateField(controller, "activeHoverKey"))

        invokePrivate(controller, "scheduleHide")
        waitUntilNull(controller, "activeHoverKey")

        assertNull(readPrivateField(controller, "latestHoverRequest"))
        assertNull(readPrivateField(controller, "popup"))
    }

    fun testSwitchingHoveredTokenReplacesVisiblePopup() {
        configureCss(
            """
            :root {
                --tui-first: #111111;
                --tui-second: #222222;
            }

            .demo {
                color: var(--tui-first);
                background: var(--tui-second);
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor
        val controller = project.service<DesignTokenHoverPopupController>()
        val firstOffset = editor.document.text.lastIndexOf("--tui-first") + 3
        val secondOffset = editor.document.text.lastIndexOf("--tui-second") + 3

        controller.mouseMoved(editorMouseEvent(firstOffset))
        val firstKey = requireNotNull(waitForPrivateField(controller, "popupKey"))

        controller.mouseMoved(editorMouseEvent(secondOffset))
        waitUntil {
            val currentKey = readPrivateField(controller, "popupKey")

            currentKey != null && currentKey != firstKey
        }

        val secondKey = readPrivateField(controller, "popupKey")

        assertNotNull(secondKey)
        assertFalse(firstKey == secondKey)
        controller.dismissHover(editor)
    }

    fun testMissingTokenShowsNotFoundPopupWithSuggestions() {
        configureCss(
            """
            :root {
                --tui-text-primary: #ff0000;
            }

            .demo {
                color: var(--tui-text-primari);
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor
        val controller = project.service<DesignTokenHoverPopupController>()
        val offset = editor.document.text.lastIndexOf("--tui-text-primari") + 3

        controller.mouseMoved(editorMouseEvent(offset))

        try {
            val panel =
                requireNotNull(
                    waitForPrivateField(controller, "popupContent"),
                ) as DesignTokenHoverPopupPanel

            assertNotNull(readPrivateField(controller, "popupKey"))
            assertTrue(panel.componentCount > 0)
        } finally {
            controller.dismissHover(editor)
        }
    }

    fun testPopupResizeCallbackAndCloseListenerClearControllerState() {
        configureCss(
            """
            :root {
                --tui-text-primary: #ff0000;
            }

            .demo {
                color: var(--tui-text-primary);
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor
        val controller = project.service<DesignTokenHoverPopupController>()
        val offset = editor.document.text.lastIndexOf("--tui-text-primary") + 3

        controller.mouseMoved(editorMouseEvent(offset))

        val panel =
            requireNotNull(
                waitForPrivateField(controller, "popupContent"),
            ) as DesignTokenHoverPopupPanel
        val popup = requireNotNull(readPrivateField(controller, "popup")) as JBPopup

        runInEdtAndGet {
            panel.showModel(
                DesignTokenHoverPopupModel.notFound(
                    tokenName = "--tui-another",
                    suggestions = listOf("--tui-text-primary"),
                ),
            )
        }
        runInEdtAndGet { popup.cancel() }

        waitUntilNull(controller, "popup")

        assertNull(readPrivateField(controller, "popupContent"))
        assertNull(readPrivateField(controller, "popupKey"))
        assertNull(readPrivateField(controller, "activeHoverKey"))
        assertNull(readPrivateField(controller, "latestHoverRequest"))
    }

    fun testDismissHoverIgnoresEditorFromAnotherProject() {
        configureCss(".demo { color: var(--tui-text-primary); }")
        val controller = project.service<DesignTokenHoverPopupController>()
        val foreignEditor =
            Proxy.newProxyInstance(
                Editor::class.java.classLoader,
                arrayOf(Editor::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "getProject" -> null
                    "toString" -> "ForeignEditor"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> null
                }
            } as Editor

        controller.dismissHover(foreignEditor)

        assertNull(readPrivateField(controller, "activeHoverKey"))
        assertNull(readPrivateField(controller, "latestHoverRequest"))
    }

    fun testNavigateToDefinitionHandlesMissingAndExistingFiles() {
        configureCss(".demo { color: var(--tui-text-primary); }")
        val controller = project.service<DesignTokenHoverPopupController>()
        val missing = tempRoot.resolve("missing.css")

        invokePrivate(
            controller,
            "navigateToDefinition",
            DesignTokenNavigationTarget(missing, 1),
        )

        val existing = tempRoot.resolve("definition.css")

        Files.writeString(existing, ":root { --tui-text-primary: red; }")
        requireNotNull(
            LocalFileSystem
                .getInstance()
                .refreshAndFindFileByNioFile(existing),
        )

        invokePrivate(
            controller,
            "navigateToDefinition",
            DesignTokenNavigationTarget(existing, 1),
        )

        assertNull(readPrivateField(controller, "activeHoverKey"))
        assertNull(readPrivateField(controller, "latestHoverRequest"))
    }

    fun testSelectionThatAppearsDuringDelayCancelsPendingRequest() {
        configureCss(".demo { color: var(--tui-text-primary); }")
        val editor = myFixture.editor
        val controller = project.service<DesignTokenHoverPopupController>()
        val offset = editor.document.text.indexOf("--tui-text-primary") + 3

        controller.mouseMoved(editorMouseEvent(offset))
        assertNotNull(readPrivateField(controller, "activeHoverKey"))

        editor.selectionModel.setSelection(0, 1)
        waitUntilNull(controller, "activeHoverKey")

        assertNull(readPrivateField(controller, "latestHoverRequest"))
        editor.selectionModel.removeSelection()
    }

    fun testMovingAwayFromVisiblePopupSchedulesAndCompletesHide() {
        configureCss(
            """
            :root {
                --tui-text-primary: #ff0000;
            }

            .demo {
                color: var(--tui-text-primary);
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor
        val controller = project.service<DesignTokenHoverPopupController>()
        val offset = editor.document.text.lastIndexOf("--tui-text-primary") + 3

        controller.mouseMoved(editorMouseEvent(offset))
        val panel =
            requireNotNull(
                waitForPrivateField(controller, "popupContent"),
            ) as DesignTokenHoverPopupPanel
        assertTrue(panel.componentCount > 0)

        controller.mouseMoved(editorMouseEvent(0))
        waitUntilNull(controller, "popup")

        assertNull(readPrivateField(controller, "activeHoverKey"))
        assertNull(readPrivateField(controller, "latestHoverRequest"))
    }

    fun testScheduleHideNoopsWithoutPopupOrActiveHover() {
        val controller = project.service<DesignTokenHoverPopupController>()

        invokePrivate(controller, "scheduleHide")

        assertNull(readPrivateField(controller, "pendingHideJob"))
    }

    fun testShowPopupDuplicateAndBlockedGuards() {
        configureCss(
            """
            :root {
                --tui-text-primary: #ff0000;
            }

            .demo {
                color: var(--tui-text-primary);
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor
        val controller = project.service<DesignTokenHoverPopupController>()
        val offset = editor.document.text.lastIndexOf("--tui-text-primary") + 3

        controller.mouseMoved(editorMouseEvent(offset))
        val key = requireNotNull(waitForPrivateField(controller, "popupKey"))
        val popup = requireNotNull(readPrivateField(controller, "popup"))
        val visiblePopup = visiblePopupStub()
        val popupField = controller.javaClass.getDeclaredField("popup").apply { isAccessible = true }
        val popupKeyField = controller.javaClass.getDeclaredField("popupKey").apply { isAccessible = true }
        val activeKeyField = controller.javaClass.getDeclaredField("activeHoverKey").apply { isAccessible = true }

        runInEdtAndGet {
            popup.cancel()
            popupField.set(controller, visiblePopup)
            popupKeyField.set(controller, key)
            activeKeyField.set(controller, key)
        }

        invokePrivate(
            controller,
            "showLoadingPopup",
            editor,
            Point(0, 0),
            key,
            "--tui-text-primary",
        )
        assertSame(visiblePopup, readPrivateField(controller, "popup"))

        editor.selectionModel.setSelection(0, 1)
        runInEdtAndGet { popupKeyField.set(controller, null) }
        invokePrivate(
            controller,
            "showPopup",
            editor,
            Point(0, 0),
            key,
            { _: DesignTokenHoverPopupPanel -> },
        )
        assertSame(visiblePopup, readPrivateField(controller, "popup"))
        editor.selectionModel.removeSelection()

        controller.dismissHover(editor)
    }

    fun testPopupWidthUsesGraphicsConfigurationWhenAvailable() {
        val configuration =
            object : GraphicsConfiguration() {
                override fun getDevice(): GraphicsDevice? = null

                override fun getColorModel(): ColorModel = ColorModel.getRGBdefault()

                override fun getColorModel(transparency: Int): ColorModel = ColorModel.getRGBdefault()

                override fun getDefaultTransform(): AffineTransform = AffineTransform()

                override fun getNormalizingTransform(): AffineTransform = AffineTransform()

                override fun getBounds(): Rectangle = Rectangle(0, 0, 600, 800)
            }
        val component =
            object : JPanel() {
                override fun getGraphicsConfiguration(): GraphicsConfiguration = configuration
            }
        val editor =
            Proxy.newProxyInstance(
                Editor::class.java.classLoader,
                arrayOf(Editor::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "getContentComponent" -> component
                    "toString" -> "GraphicsEditor"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> null
                }
            } as Editor
        val method =
            Class
                .forName("org.taigaui.designtokens.documentation.DesignTokenHoverPopupControllerKt")
                .declaredMethods
                .single { candidate ->
                    candidate.name == "calculateDesignTokenPopupWidth" &&
                        candidate.parameterCount == 1
                }.apply { isAccessible = true }

        val width = method.invoke(null, editor) as Int

        assertTrue(width in 1..559)
    }

    private fun Container.containsLabel(text: String): Boolean =
        components.any { component ->
            (component as? JLabel)?.text == text ||
                (component as? Container)?.containsLabel(text) == true
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
        repeat(500) {
            UIUtil.dispatchAllInvocationEvents()
            readPrivateField(target, fieldName)?.let { return it }
            Thread.sleep(10)
        }

        return readPrivateField(target, fieldName)
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(1500) {
            UIUtil.dispatchAllInvocationEvents()

            if (condition()) {
                return
            }

            Thread.sleep(10)
        }

        assertTrue(condition())
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
