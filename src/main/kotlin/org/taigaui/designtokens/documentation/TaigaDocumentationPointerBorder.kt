package org.taigaui.designtokens.documentation

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Polygon
import java.awt.RenderingHints
import javax.swing.border.AbstractBorder

/** A small anchor marker, using the card's own colors and scaled coordinates. */
@Suppress("LongParameterList")
internal class TaigaDocumentationPointerBorder : AbstractBorder() {
    var pointerX: Int = JBUI.scale(32)
    var pointerVisible: Boolean = true

    override fun getBorderInsets(component: Component): Insets = JBUI.insets(10, 0, 0, 0)

    override fun getBorderInsets(
        component: Component,
        insets: Insets,
    ): Insets = insets.apply { set(JBUI.scale(10), 0, 0, 0) }

    override fun paintBorder(
        component: Component,
        graphics: Graphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val copy = graphics.create() as Graphics2D
        val half = JBUI.scale(8)
        val top = JBUI.scale(10)
        val center = pointerX.coerceIn(half + 1, (width - half - 1).coerceAtLeast(half + 1))
        val pointer =
            Polygon(
                intArrayOf(x + center - half, x + center, x + center + half),
                intArrayOf(
                    y + top,
                    y + 1,
                    y + top,
                ),
                3,
            )
        copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        copy.color = CARD_MUTED_FOREGROUND
        copy.drawRoundRect(x, y + top, width - 1, height - top - 1, JBUI.scale(10), JBUI.scale(10))
        if (!pointerVisible) {
            copy.dispose()
            return
        }
        copy.color = DESIGN_TOKEN_POPUP_BACKGROUND
        copy.fillPolygon(pointer)
        copy.color = CARD_MUTED_FOREGROUND
        copy.drawLine(x + center - half, y + top, x + center, y + 1)
        copy.drawLine(x + center, y + 1, x + center + half, y + top)
        copy.dispose()
    }
}
