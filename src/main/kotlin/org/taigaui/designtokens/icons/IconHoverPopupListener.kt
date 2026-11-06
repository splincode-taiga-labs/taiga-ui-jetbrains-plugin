package org.taigaui.designtokens.icons

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener

internal class IconHoverPopupListener :
    EditorMouseListener,
    EditorMouseMotionListener {
    override fun mousePressed(event: EditorMouseEvent) {
        event.dismissIconHoverPopup()
    }

    override fun mouseDragged(event: EditorMouseEvent) {
        event.dismissIconHoverPopup()
    }

    override fun mouseExited(event: EditorMouseEvent) {
        event.dismissIconHoverPopup()
    }

    override fun mouseMoved(event: EditorMouseEvent) {
        val editor = event.editor
        val project = editor.project ?: return

        if (LookupManager.getInstance(project).activeLookup == null) {
            project.service<IconHoverPopupController>().mouseMoved(event)
        } else {
            project.service<IconHoverPopupController>().dismissHover(editor)
        }
    }
}

private fun EditorMouseEvent.dismissIconHoverPopup() {
    val project = editor.project ?: return

    project.service<IconHoverPopupController>().dismissHover(editor)
}
