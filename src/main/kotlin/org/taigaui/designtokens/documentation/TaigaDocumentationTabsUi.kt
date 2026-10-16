package org.taigaui.designtokens.documentation

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Insets
import javax.swing.JTabbedPane
import javax.swing.plaf.basic.BasicTabbedPaneUI

internal class TaigaDocumentationTabs : JTabbedPane() {
    init {
        setUI(TaigaDocumentationTabsUi())
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        foreground = CARD_MUTED_FOREGROUND
    }

    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        val largestContent = (0 until tabCount).maxOfOrNull { getComponentAt(it).preferredSize.height } ?: 0
        val visibleContent = selectedComponent?.preferredSize?.height ?: 0
        return Dimension(size.width, size.height - largestContent + visibleContent)
    }
}

/** Keep the native tab interaction and accessibility with a quiet, underlined presentation. */
@Suppress("LongParameterList")
internal class TaigaDocumentationTabsUi : BasicTabbedPaneUI() {
    override fun getTabInsets(
        tabPlacement: Int,
        tabIndex: Int,
    ): Insets = JBUI.insets(8, 10)

    override fun paintTabBackground(
        g: Graphics,
        placement: Int,
        index: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        selected: Boolean,
    ) = Unit

    override fun paintTabBorder(
        g: Graphics,
        placement: Int,
        index: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        selected: Boolean,
    ) {
        if (selected) {
            g.color = DESIGN_TOKEN_POPUP_LINK_COLOR
            g.fillRect(x, y + height - JBUI.scale(2), width, JBUI.scale(2))
        }
    }

    override fun paintContentBorder(
        g: Graphics,
        placement: Int,
        selected: Int,
    ) = Unit
}
