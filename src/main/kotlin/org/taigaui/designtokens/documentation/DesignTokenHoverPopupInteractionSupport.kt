package org.taigaui.designtokens.documentation

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import java.awt.MouseInfo
import java.awt.Point
import javax.swing.JComponent
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

internal fun DesignTokenHoverPopupPanel?.containsPointer(): Boolean =
    containsScreenPointer(
        content = this,
        isShowing = this?.isShowing == true,
        screenPointer = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull(),
    )

internal fun containsScreenPointer(
    content: JComponent?,
    isShowing: Boolean,
    screenPointer: Point?,
): Boolean {
    if (content == null || !isShowing || screenPointer == null) {
        return false
    }

    val localPointer = Point(screenPointer)

    SwingUtilities.convertPointFromScreen(localPointer, content)

    return content.contains(localPointer)
}
