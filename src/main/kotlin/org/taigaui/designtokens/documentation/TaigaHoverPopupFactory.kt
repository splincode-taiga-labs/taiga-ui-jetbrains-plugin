package org.taigaui.designtokens.documentation

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.ui.JBUI
import java.awt.Rectangle
import javax.swing.JComponent

/** Use the standard JetBrains popup lifecycle, with a side anchor only for passive hover. */
@Suppress("LongParameterList")
internal fun createTaigaDocumentationPopup(
    project: Project,
    content: JComponent,
    preferredFocus: JComponent,
    requestFocus: Boolean,
    explicit: Boolean,
    pinned: Boolean,
    sourceBounds: () -> Rectangle,
): JBPopup {
    val builder =
        JBPopupFactory
            .getInstance()
            .createComponentPopupBuilder(content, preferredFocus)
            .setProject(project)
            .setRequestFocus(requestFocus)
            .setFocusable(true)
            .setShowBorder(explicit || pinned)
            .setShowShadow(explicit || pinned)
            .setCancelOnClickOutside(!pinned)
            .setCancelOnOtherWindowOpen(!pinned)
            .setCancelOnWindowDeactivation(!pinned)
            .setMovable(pinned)
            .setResizable(pinned)

    builder.setCancelOnMouseOutCallback { event ->
        if (pinned || explicit) {
            false
        } else {
            val source = sourceBounds()
            source.grow(JBUI.scale(20), JBUI.scale(12))
            !source.contains(event.locationOnScreen)
        }
    }
    return builder.createPopup()
}
