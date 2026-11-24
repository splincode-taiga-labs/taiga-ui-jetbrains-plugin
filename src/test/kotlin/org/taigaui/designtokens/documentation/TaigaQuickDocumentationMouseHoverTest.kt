package org.taigaui.designtokens.documentation

import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import kotlinx.coroutines.Job
import java.awt.event.MouseEvent

class TaigaQuickDocumentationMouseHoverTest : TaigaQuickDocumentationControllerTestCase() {
    fun testMouseExitWithoutAnInteractiveCardClearsPendingHover() {
        configure("<button tuiButton>Save</button>", "tuiButton")

        controller.mouseExited(myFixture.editor)

        assertNull(panelOrNull())
        assertNull(documentationField<Any?>(controller, "pendingKey"))
        assertNull(documentationField<Any?>(controller, "currentRequest"))
    }

    fun testRepeatedMouseMoveKeepsOnePendingHoverResolution() {
        configure("<button tuiButton size=\"m\">Save</button>", "size")
        val settings = EditorSettingsExternalizable.getInstance()
        val previous = settings.isShowQuickDocOnMouseOverElement
        try {
            settings.isShowQuickDocOnMouseOverElement = true
            val event = mouseMoveOver("size")
            controller.mouseMoved(event)
            val first = documentationField<Job>(controller, "resolutionJob")

            controller.mouseMoved(event)
            assertSame(first, documentationField<Job>(controller, "resolutionJob"))
            assertNotNull(documentationField<Any?>(controller, "pendingKey"))
        } finally {
            controller.dismissHover(force = true)
            settings.isShowQuickDocOnMouseOverElement = previous
        }
    }

    fun testMouseMoveBetweenIdentifiersCancelsStaleResolution() {
        configure("<button tuiButton size=\"m\">Save</button>", "size")
        val settings = EditorSettingsExternalizable.getInstance()
        val previous = settings.isShowQuickDocOnMouseOverElement
        try {
            settings.isShowQuickDocOnMouseOverElement = true
            controller.mouseMoved(mouseMoveOver("tuiButton"))
            val first = documentationField<Job>(controller, "resolutionJob")

            controller.mouseMoved(mouseMoveOver("size"))
            val second = documentationField<Job>(controller, "resolutionJob")
            assertNotSame(first, second)
            assertTrue(first.isCancelled)
        } finally {
            controller.dismissHover(force = true)
            settings.isShowQuickDocOnMouseOverElement = previous
        }
    }

    fun testMovingOntoWhitespaceLeavesNoPendingHoverTarget() {
        configure("<button tuiButton>Save</button>\n<button tuiButton>Next</button>", "tuiButton")
        val settings = EditorSettingsExternalizable.getInstance()
        val previous = settings.isShowQuickDocOnMouseOverElement
        try {
            settings.isShowQuickDocOnMouseOverElement = true
            controller.mouseMoved(mouseMoveOver("\n", middle = false))
            assertNull(documentationField<Any?>(controller, "pendingKey"))
        } finally {
            controller.dismissHover(force = true)
            settings.isShowQuickDocOnMouseOverElement = previous
        }
    }

    fun testPassiveHoverOpensMemberAndCanExpandToTheFullCard() {
        configure("<button tuiButton size=\"m\">Save</button>", "size")
        val settings = EditorSettingsExternalizable.getInstance()
        val previous = settings.isShowQuickDocOnMouseOverElement

        try {
            settings.isShowQuickDocOnMouseOverElement = true
            controller.mouseMoved(mouseMoveOver("size"))
            await("passive member documentation") { panelOrNull() != null }

            val compact = panel()
            assertTrue(compact.compactHover)
            assertEquals("size", (view().resolved as TaigaResolvedDocumentation.Member).property.name)
            controller.mouseMoved(mouseMoveOver("size"))
            assertSame(compact, panel())

            requireNotNull(actions().openFullCard).invoke()
            assertNotSame(compact, panel())
            assertFalse(panel().compactHover)
            assertTrue(view().expanded)
        } finally {
            controller.dismissHover(force = true)
            settings.isShowQuickDocOnMouseOverElement = previous
        }
    }

    fun testMouseListenerCancelsPendingRequestsOnPressDragAndExit() {
        configure("<button tuiButton size=\"m\">Save</button>", "size")
        val settings = EditorSettingsExternalizable.getInstance()
        val previous = settings.isShowQuickDocOnMouseOverElement
        val listener = TaigaQuickDocumentationHoverPopupListener()
        val event = mouseMoveOver("size")

        try {
            settings.isShowQuickDocOnMouseOverElement = true

            listener.mouseMoved(event)
            val pressed = documentationField<Job>(controller, "resolutionJob")
            listener.mousePressed(event)
            assertDismissed(pressed)

            listener.mouseMoved(event)
            val dragged = documentationField<Job>(controller, "resolutionJob")
            listener.mouseDragged(event)
            assertDismissed(dragged)

            listener.mouseMoved(event)
            val exited = documentationField<Job>(controller, "resolutionJob")
            listener.mouseExited(event)
            assertDismissed(exited)
        } finally {
            controller.dismissHover(force = true)
            settings.isShowQuickDocOnMouseOverElement = previous
        }
    }

    fun testPassiveHoverOnOrdinaryHtmlDoesNotPublishDocumentation() {
        configure("<button title=\"plain\">Save</button>", "title")
        val settings = EditorSettingsExternalizable.getInstance()
        val previous = settings.isShowQuickDocOnMouseOverElement

        try {
            settings.isShowQuickDocOnMouseOverElement = true
            controller.mouseMoved(mouseMoveOver("title"))
            val job = documentationField<Job>(controller, "resolutionJob")
            await("unresolved passive hover") { job.isCompleted }

            assertNull(panelOrNull())
            assertNull(documentationField<Any?>(controller, "pendingKey"))
            assertTrue(shown.isEmpty())
        } finally {
            controller.dismissHover(force = true)
            settings.isShowQuickDocOnMouseOverElement = previous
        }
    }

    private fun mouseMoveOver(
        needle: String,
        middle: Boolean = true,
    ): EditorMouseEvent {
        val editor = myFixture.editor
        val offset = myFixture.file.text.indexOf(needle) + if (middle) 1 else 0
        val mouse =
            MouseEvent(
                editor.contentComponent,
                MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(),
                0,
                20,
                20,
                0,
                false,
            )
        return EditorMouseEvent(
            editor,
            mouse,
            EditorMouseEventArea.EDITING_AREA,
            offset,
            editor.offsetToLogicalPosition(offset),
            editor.offsetToVisualPosition(offset),
            true,
            null,
            null,
            null,
        )
    }
}
