package org.taigaui.designtokens.documentation

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import java.awt.MouseInfo
import javax.swing.SwingUtilities

internal fun Project.hasActiveCompletionLookup(): Boolean =
    LookupManager.getInstance(this).activeLookup?.isCompletion == true

internal fun Project.blocksDesignTokenPopup(editor: Editor): Boolean =
    hasActiveCompletionLookup() || editor.isDisposed || editor.selectionModel.hasSelection()

internal fun createDesignTokenPopup(
    project: Project,
    panel: DesignTokenHoverPopupPanel,
): JBPopup =
    JBPopupFactory
        .getInstance()
        .createComponentPopupBuilder(panel, panel)
        .setProject(project)
        .setRequestFocus(false)
        .setFocusable(true)
        .setCancelOnClickOutside(true)
        .setCancelOnOtherWindowOpen(true)
        .setCancelOnWindowDeactivation(true)
        .setCancelKeyEnabled(true)
        .setMovable(false)
        .setResizable(false)
        .createPopup()

internal fun DesignTokenHoverPopupPanel?.containsPointer(): Boolean {
    val content = this?.takeIf { component -> component.isShowing } ?: return false
    val pointer = MouseInfo.getPointerInfo()?.location ?: return false

    SwingUtilities.convertPointFromScreen(pointer, content)

    return content.contains(pointer)
}
