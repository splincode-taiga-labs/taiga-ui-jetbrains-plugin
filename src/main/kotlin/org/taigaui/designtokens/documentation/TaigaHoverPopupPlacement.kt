package org.taigaui.designtokens.documentation

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle

internal enum class TaigaHoverPointerSide {
    LEFT,
    RIGHT,
    NONE,
}

internal data class TaigaHoverPopupPlacement(
    val location: Point,
    val pointerSide: TaigaHoverPointerSide,
)

/** Prefer a free side of the source token; never overlay it when a side does not fit. */
internal fun calculateTaigaHoverPopupPlacement(
    target: Rectangle,
    popup: Dimension,
    screen: Rectangle,
    gap: Int,
): TaigaHoverPopupPlacement {
    val right = target.x + target.width + gap
    val left = target.x - gap - popup.width
    val verticalMax = (screen.y + screen.height - popup.height).coerceAtLeast(screen.y)
    val alignedY = (target.y + target.height / 2 - popup.height / 2).coerceIn(screen.y, verticalMax)

    if (right + popup.width <= screen.x + screen.width) {
        return TaigaHoverPopupPlacement(Point(right, alignedY), TaigaHoverPointerSide.LEFT)
    }
    if (left >= screen.x) {
        return TaigaHoverPopupPlacement(Point(left, alignedY), TaigaHoverPointerSide.RIGHT)
    }

    // On narrow screens use the usual above/below tooltip fallback without covering the hovered token.
    val maxX = (screen.x + screen.width - popup.width).coerceAtLeast(screen.x)
    val fallbackX = target.x.coerceIn(screen.x, maxX)
    val below = target.y + target.height + gap
    val above = target.y - gap - popup.height
    val fallbackY = if (below + popup.height <= screen.y + screen.height) below else above
    return TaigaHoverPopupPlacement(
        Point(fallbackX, fallbackY.coerceIn(screen.y, verticalMax)),
        TaigaHoverPointerSide.NONE,
    )
}
