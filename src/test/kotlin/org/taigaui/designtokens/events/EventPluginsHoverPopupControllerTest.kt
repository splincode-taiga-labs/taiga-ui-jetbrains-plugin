package org.taigaui.designtokens.events

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JPanel

class EventPluginsHoverPopupControllerTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "node_modules/@angular/core/package.json",
            """{"name":"@angular/core","version":"22.0.0","types":"index.d.ts"}""",
        )
        myFixture.addFileToProject(
            "node_modules/@angular/core/index.d.ts",
            """
            export interface DirectiveMetadata {
                selector?: string;
                host?: Record<string, string>;
            }
            export declare function Directive(metadata: DirectiveMetadata): ClassDecorator;
            """.trimIndent(),
        )
    }

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

    fun testValidHoverShowsPopupAfterDelay() {
        myFixture.configureByText(
            "event-plugin-popup.html",
            """<button (click.stop.prevent)="submit()">Save</button>""",
        )
        val editor = myFixture.editor
        val offset = editor.document.text.indexOf("stop") + 2
        val controller = project.service<EventPluginsHoverPopupController>()

        controller.mouseMoved(editorMouseEvent(offset, EditorMouseEventArea.EDITING_AREA))

        val popup = requireNotNull(waitForPrivateField(controller, "popup")) as com.intellij.openapi.ui.popup.JBPopup

        runInEdtAndGet { popup.cancel() }
        waitForPrivateFieldValue(controller, "popup", expectedNull = true)

        assertNull(readPrivateField(controller, "activeKey"))
        assertNull(readPrivateField(controller, "hoverJob"))
    }

    fun testVisiblePopupRejectsDuplicateShowRequest() {
        myFixture.configureByText(
            "event-plugin-duplicate.html",
            """<button (click.stop.prevent)="submit()">Save</button>""",
        )
        val editor = myFixture.editor
        val offset = editor.document.text.indexOf("stop") + 2
        val controller = project.service<EventPluginsHoverPopupController>()

        controller.mouseMoved(editorMouseEvent(offset))
        val popup = requireNotNull(waitForPrivateField(controller, "popup"))
        val reference = requireNotNull(EventPluginBindingAtOffsetFinder.find(editor.document.text, offset))
        val requestClass = Class.forName("org.taigaui.designtokens.events.EventPluginsHoverRequest")
        val request =
            requestClass.declaredConstructors
                .single()
                .apply { isAccessible = true }
                .newInstance(
                    editor,
                    reference,
                    Point(0, 0),
                    editor.document.modificationStamp,
                )
        val showPopup =
            controller.javaClass.declaredMethods
                .single { method ->
                    method.name == "showPopup" && method.parameterCount == 1
                }.apply { isAccessible = true }

        showPopup.invoke(controller, request)

        assertSame(popup, readPrivateField(controller, "popup"))
        controller.dismissHover(editor)
    }

    fun testRepeatedSuppressionForSameEditorIsNoop() {
        myFixture.configureByText(
            "event-plugin-suppression.html",
            """<button (click.stop)="submit()">Save</button>""",
        )
        val controller = project.service<EventPluginsHoverPopupController>()

        invokePrivate(controller, "suppressNativeHover", myFixture.editor)
        val suppressed = readPrivateField(controller, "nativeHoverSuppressedEditor")
        invokePrivate(controller, "suppressNativeHover", myFixture.editor)

        assertSame(suppressed, readPrivateField(controller, "nativeHoverSuppressedEditor"))
        controller.dismissHover()
    }

    fun testMouseMoveFallsBackToAngularHostMetadata() {
        val file =
            myFixture.addFileToProject(
                "src/directive-hover.ts",
                """
                import {Directive} from '@angular/core';

                @Directive({
                    selector: '[example]',
                    host: {'(keydown.enter.stop)': 'onKey()'},
                })
                export class ExampleDirective {}
                """.trimIndent(),
            )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val offset = myFixture.editor.document.text.indexOf("enter") + 2
        val controller = project.service<EventPluginsHoverPopupController>()

        controller.mouseMoved(editorMouseEvent(offset))

        assertNotNull(waitForPrivateField(controller, "activeKey"))
        controller.dismissHover()
    }

    fun testListenerDismissesWhenCompletionLookupIsActive() {
        myFixture.configureByText("lookup.html", "<di<caret>>")
        val lookup = myFixture.completeBasic()
        val activeLookup = runInEdtAndGet { LookupManager.getActiveLookup(myFixture.editor) }

        assertNotNull(lookup)
        assertNotNull(activeLookup)

        val listener = EventPluginsHoverPopupListener()
        listener.mouseMoved(
            editorMouseEvent(
                myFixture.editor.caretModel.offset.coerceAtLeast(0),
                EditorMouseEventArea.EDITING_AREA,
            ),
        )

        runInEdtAndGet { LookupManager.getInstance(project).hideActiveLookup() }
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

    fun testListenerForwardsMovePressAndDragToController() {
        myFixture.configureByText(
            "event-plugin-listener.html",
            """<button (click.stop.prevent)="submit()">Save</button>""",
        )
        val controller = project.service<EventPluginsHoverPopupController>()
        val listener = EventPluginsHoverPopupListener()
        val offset =
            myFixture.editor.document.text
                .indexOf("stop") + 2
        val event = editorMouseEvent(offset, EditorMouseEventArea.EDITING_AREA)

        listener.mouseMoved(event)
        assertNotNull(readPrivateField(controller, "activeKey"))

        listener.mousePressed(event)
        assertNull(readPrivateField(controller, "activeKey"))

        listener.mouseMoved(event)
        assertNotNull(readPrivateField(controller, "activeKey"))

        listener.mouseDragged(event)
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
