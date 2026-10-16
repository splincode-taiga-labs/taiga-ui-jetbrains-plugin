package org.taigaui.designtokens.documentation

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JButton

internal class TaigaDocumentationLiteralButton(
    value: String,
    copy: () -> Unit,
) : JButton("'${if (value.length > 8) value.take(7) + "…" else value}'") {
    init {
        font = taigaDocumentationCodeFont()
        foreground = JBColor(Color(119, 89, 0), Color(229, 207, 125))
        toolTipText = "Copy '$value'"
        getAccessibleContext().accessibleName = "Copy literal '$value'"
        border = JBUI.Borders.empty(3, 7)
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        addActionListener { copy() }
    }

    override fun paintComponent(graphics: Graphics) {
        val copy = graphics.create() as Graphics2D
        copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        copy.color = JBColor(Color(232, 236, 242), Color(43, 46, 52))
        copy.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
        if (hasFocus()) {
            copy.color = DESIGN_TOKEN_POPUP_LINK_COLOR
            copy.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(8), JBUI.scale(8))
        }
        copy.dispose()
        super.paintComponent(graphics)
    }
}
