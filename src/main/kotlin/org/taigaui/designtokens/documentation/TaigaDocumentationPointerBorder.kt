package org.taigaui.designtokens.documentation

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Polygon
import java.awt.RenderingHints
import javax.swing.border.AbstractBorder

/** A side-facing pointer connected to the hovered source, drawn without an opaque background strip. */
internal class TaigaDocumentationPointerBorder : AbstractBorder() {
    var pointerY: Int = JBUI.scale(32)
    var pointerSide: TaigaHoverPointerSide = TaigaHoverPointerSide.LEFT

    override fun getBorderInsets(component: Component): Insets =
        when (pointerSide) {
            TaigaHoverPointerSide.LEFT -> JBUI.insets(0, 10, 0, 0)
            TaigaHoverPointerSide.RIGHT -> JBUI.insets(0, 0, 0, 10)
            TaigaHoverPointerSide.NONE -> JBUI.insets(0)
        }

    override fun getBorderInsets(component: Component, insets: Insets): Insets =
        getBorderInsets(component).let { insets.apply { set(it.top, it.left, it.bottom, it.right) } }

    override fun paintBorder(component: Component, graphics: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val copy = graphics.create() as Graphics2D
        try {
            val inset = JBUI.scale(10)
            val left = x + if (pointerSide == TaigaHoverPointerSide.LEFT) inset else 0
            val bodyWidth = width - if (pointerSide == TaigaHoverPointerSide.NONE) 0 else inset
            val half = JBUI.scale(8)
            val middle = pointerY.coerceIn(half + 2, (height - half - 2).coerceAtLeast(half + 2))
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            copy.color = CARD_MUTED_FOREGROUND
            copy.drawRoundRect(left, y, bodyWidth - 1, height - 1, inset, inset)
            if (pointerSide == TaigaHoverPointerSide.NONE) return

            val edge = if (pointerSide == TaigaHoverPointerSide.LEFT) left else left + bodyWidth - 1
            val tip = if (pointerSide == TaigaHoverPointerSide.LEFT) x + 1 else x + width - 2
            val triangle = Polygon(intArrayOf(edge, tip, edge), intArrayOf(middle - half, middle, middle + half), 3)
            copy.color = DESIGN_TOKEN_POPUP_BACKGROUND
            copy.fillPolygon(triangle)
            copy.color = CARD_MUTED_FOREGROUND
            copy.drawLine(edge, middle - half, tip, middle)
            copy.drawLine(tip, middle, edge, middle + half)
        } finally {
            copy.dispose()
        }
    }
}
