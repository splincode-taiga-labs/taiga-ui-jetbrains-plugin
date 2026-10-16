package org.taigaui.designtokens.documentation

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.ui.JBUI
import java.awt.Rectangle

/** Use the standard JetBrains popup lifecycle, with a side anchor only for passive hover. */
internal fun createTaigaDocumentationPopup(
    project: Project,
    panel: TaigaQuickDocumentationPopupPanel,
    requestFocus: Boolean,
    explicit: Boolean,
    pinned: Boolean,
    sourceBounds: () -> Rectangle,
): JBPopup =
    JBPopupFactory
        .getInstance()
        .createComponentPopupBuilder(panel, panel.preferredFocus)
        .setProject(project)
        .setRequestFocus(requestFocus)
        .setFocusable(true)
        .setShowBorder(explicit || pinned)
        .setShowShadow(explicit || pinned)
        .setCancelOnMouseOutCallback { event ->
            if (pinned || explicit) {
                false
            } else {
                val source = sourceBounds()
                source.grow(JBUI.scale(20), JBUI.scale(12))
                !source.contains(event.locationOnScreen)
            }
        }
        .setCancelOnClickOutside(!pinned)
        .setCancelOnOtherWindowOpen(!pinned)
        .setCancelOnWindowDeactivation(!pinned)
        .setMovable(pinned)
        .setResizable(pinned)
        .createPopup()
