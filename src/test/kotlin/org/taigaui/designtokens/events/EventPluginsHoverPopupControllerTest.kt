package org.taigaui.designtokens.events

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JPanel

class EventPluginsHoverPopupControllerTest : BasePlatformTestCase() {
    fun testValidHoverRequestSuppressesNativeHoverAndCanBeDismissed() {
        myFixture.configureByText(
            "event-plugin.html",
            """<button (click.stop.prevent)="submit()">Save</button>""",
        )
        val editor = myFixture.editor
        val offset = editor.document.text.indexOf("stop") + 2
        val controller = project.service<EventPluginsHoverPopupController>()

        controller.mouseMoved(editorMouseEvent(offset, EditorMouseEventArea.EDITING_AREA))

        assertNotNull(waitForPrivateField(controller, "activeKey"))
        assertNotNull(readPrivateField(controller, "hoverJob"))

        controller.dismissHover(editor)

        assertNull(readPrivateField(controller, "activeKey"))
        assertNull(readPrivateField(controller, "hoverJob"))
    }

    fun testHoverRequestBecomesStaleAfterDocumentChange() {
        myFixture.configureByText(
            "event-plugin-stale.html",
            """<button (click.stop)="submit()">Save</button>""",
        )
        val editor = myFixture.editor
        val offset = editor.document.text.indexOf("stop") + 2
        val controller = project.service<EventPluginsHoverPopupController>()

        controller.mouseMoved(editorMouseEvent(offset, EditorMouseEventArea.EDITING_AREA))
        assertNotNull(waitForPrivateField(controller, "activeKey"))

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(0, " ")
        }
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)

        assertNull(waitForPrivateFieldValue(controller, "activeKey", expectedNull = true))
        assertNull(readPrivateField(controller, "hoverJob"))
    }

    fun testNonEditingAreaAndSelectionDismissHover() {
        myFixture.configureByText(
            "event-plugin-dismiss.html",
            """<button (click.stop)="submit()">Save</button>""",
        )
        val editor = myFixture.editor
        val offset = editor.document.text.indexOf("stop") + 2
        val controller = project.service<EventPluginsHoverPopupController>()

        controller.mouseMoved(editorMouseEvent(offset, EditorMouseEventArea.EDITING_AREA))
        assertNotNull(waitForPrivateField(controller, "activeKey"))

        controller.mouseMoved(editorMouseEvent(offset, EditorMouseEventArea.LINE_NUMBERS_AREA))
        assertNull(readPrivateField(controller, "activeKey"))

        editor.selectionModel.setSelection(0, 1)
        controller.mouseMoved(editorMouseEvent(offset, EditorMouseEventArea.EDITING_AREA))
        assertNull(readPrivateField(controller, "activeKey"))
    }

    fun testPopupPanelRendersEscapedBindingDocumentation() {
        val binding = requireNotNull(EventPluginBinding.parse("(click.stop.debounce~250ms)"))
        val panelClass = Class.forName("org.taigaui.designtokens.events.EventPluginsHoverPopupPanel")
        val constructor =
            panelClass
                .getDeclaredConstructor(EventPluginBinding::class.java)
                .apply { isAccessible = true }
        val panel = constructor.newInstance(binding) as JPanel
        val label = panel.descendants().filterIsInstance<JBLabel>().single()

        assertTrue(label.text.contains("click.stop.debounce~250ms"))
        assertTrue(label.text.contains("Modifiers"))
        assertTrue(label.text.contains("Combined behavior"))
        assertTrue(label.text.contains("@taiga-ui/event-plugins"))
    }

    private fun editorMouseEvent(
        offset: Int,
        area: EditorMouseEventArea,
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

    private fun waitForPrivateFieldValue(
        target: Any,
        fieldName: String,
        expectedNull: Boolean,
    ): Any? {
        repeat(300) {
            UIUtil.dispatchAllInvocationEvents()
            val value = readPrivateField(target, fieldName)
            val matched = if (expectedNull) value == null else value != null

            if (matched) {
                return value
            }

            Thread.sleep(10)
        }

        return readPrivateField(target, fieldName)
    }

    private fun Container.descendants(): Sequence<Component> =
        components.asSequence().flatMap { component ->
            sequenceOf(component) +
                if (component is Container) {
                    component.descendants()
                } else {
                    emptySequence()
                }
        }
}
