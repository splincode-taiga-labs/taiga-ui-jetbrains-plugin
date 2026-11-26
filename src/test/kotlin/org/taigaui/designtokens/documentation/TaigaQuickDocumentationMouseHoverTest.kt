package org.taigaui.designtokens.documentation

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.ui.popup.JBPopup
import kotlinx.coroutines.Job
import java.awt.Dimension
import java.awt.Point
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

    fun testDocumentChangeWhileHoverIsPendingPreventsStaleCard() {
        configure("<button tuiButton size=\"m\">Save</button>", "size")
        val settings = EditorSettingsExternalizable.getInstance()
        val previous = settings.isShowQuickDocOnMouseOverElement

        try {
            settings.isShowQuickDocOnMouseOverElement = true
            controller.mouseMoved(mouseMoveOver("size"))
            await("scheduled hover") { documentationField<Job?>(controller, "hoverJob") != null }
            val scheduled = documentationField<Job>(controller, "hoverJob")

            WriteCommandAction.runWriteCommandAction(project) {
                myFixture.editor.document.insertString(0, "<!-- changed -->")
            }

            await("stale hover completion") { scheduled.isCompleted }
            assertNull(panelOrNull())
            assertTrue(shown.isEmpty())
        } finally {
            controller.dismissHover(force = true)
            settings.isShowQuickDocOnMouseOverElement = previous
        }
    }

    fun testMouseExitCancelsScheduledHoverBeforeItCanShowCard() {
        configure("<button tuiButton size=\"m\">Save</button>", "size")
        val settings = EditorSettingsExternalizable.getInstance()
        val previous = settings.isShowQuickDocOnMouseOverElement

        try {
            settings.isShowQuickDocOnMouseOverElement = true
            controller.mouseMoved(mouseMoveOver("size"))
            await("scheduled hover") { documentationField<Job?>(controller, "hoverJob") != null }
            val scheduled = documentationField<Job>(controller, "hoverJob")

            controller.mouseExited(myFixture.editor)

            await("cancelled hover") { scheduled.isCompleted }
            assertTrue(scheduled.isCancelled)
            assertNull(panelOrNull())
            assertNull(documentationField<Any?>(controller, "pendingKey"))
            assertTrue(shown.isEmpty())
        } finally {
            controller.dismissHover(force = true)
            settings.isShowQuickDocOnMouseOverElement = previous
        }
    }

    fun testLeavingVisibleHoverCardClearsItAfterTheGracePeriod() {
        configure("<button tuiButton size=\"m\">Save</button>", "size")
        val settings = EditorSettingsExternalizable.getInstance()
        val previous = settings.isShowQuickDocOnMouseOverElement

        try {
            settings.isShowQuickDocOnMouseOverElement = true
            controller.mouseMoved(mouseMoveOver("size"))
            await("passive member documentation") { panelOrNull() != null }
            val card = panel()
            val popup = documentationField<JBPopup>(controller, "popup")
            val visible = documentationPopupWithGeometry(popup, Dimension(380, 240), Point(100, 100))
            controller.javaClass
                .getDeclaredField("popup")
                .apply { isAccessible = true }
                .set(controller, visible)

            controller.mouseExited(myFixture.editor)
            assertNotNull(documentationField<Job?>(controller, "hideJob"))
            assertSame(card, panel())
            await("hover grace period") { panelOrNull() == null }
            assertNull(documentationField<Any?>(controller, "currentRequest"))
            assertNull(documentationField<Any?>(controller, "hideJob"))
        } finally {
            controller.dismissHover(force = true)
            settings.isShowQuickDocOnMouseOverElement = previous
        }
    }

    fun testSwitchingVisibleHoverTargetsReusesThePopup() {
        configure("<button tuiButton size=\"m\" [enabled]=\"true\">Save</button>", "size")
        val settings = EditorSettingsExternalizable.getInstance()
        val previous = settings.isShowQuickDocOnMouseOverElement

        try {
            settings.isShowQuickDocOnMouseOverElement = true
            controller.mouseMoved(mouseMoveOver("size"))
            await("first member documentation") { panelOrNull() != null }
            val first = panel()
            val popup = documentationField<JBPopup>(controller, "popup")
            val visible = documentationPopupWithGeometry(popup, Dimension(380, 240), Point(100, 100))
            controller.javaClass
                .getDeclaredField("popup")
                .apply { isAccessible = true }
                .set(controller, visible)

            controller.mouseMoved(mouseMoveOver("enabled"))
            assertSame(first, panel())
            await("second member documentation") { panelOrNull()?.let { it !== first } == true }

            assertSame(visible, documentationField<JBPopup>(controller, "popup"))
            assertEquals("enabled", (view().resolved as TaigaResolvedDocumentation.Member).property.name)
            assertEquals(1, shown.size)
        } finally {
            controller.dismissHover(force = true)
            settings.isShowQuickDocOnMouseOverElement = previous
        }
    }

    fun testPinnedCardIgnoresMouseMovementOverAnotherMember() {
        open("<button tuiButton size=\"m\" [enabled]=\"true\">Save</button>", "size")
        requireNotNull(actions().togglePin).invoke()
        val pinned = panel()

        controller.mouseMoved(mouseMoveOver("enabled"))

        assertSame(pinned, panel())
        assertEquals("size", (view().resolved as TaigaResolvedDocumentation.Member).property.name)
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
