package org.taigaui.designtokens.documentation

import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.Point
import javax.swing.JComponent
import javax.swing.SwingUtilities

internal fun JComponent.hasTaigaFocus(): Boolean =
    KeyboardFocusManager
        .getCurrentKeyboardFocusManager()
        .focusOwner
        ?.let { SwingUtilities.isDescendingFrom(it, this) } == true

internal fun JComponent.containsTaigaPointer(): Boolean =
    isShowing &&
        MouseInfo
            .getPointerInfo()
            ?.location
            ?.let(::Point)
            ?.also { SwingUtilities.convertPointFromScreen(it, this) }
            ?.let(::contains) ?: false
