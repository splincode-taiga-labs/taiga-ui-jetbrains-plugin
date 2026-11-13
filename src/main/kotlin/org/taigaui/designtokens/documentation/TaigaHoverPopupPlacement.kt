package org.taigaui.designtokens.documentation

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.ui.ScreenUtil
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal fun taigaDocumentationHoverDelay(switching: Boolean): Duration =
    if (switching) 200.milliseconds else 850.milliseconds

internal fun shouldRepositionTaigaHoverCard(
    current: Point,
    desired: Point,
    tolerance: Int = 3,
): Boolean = abs(current.x - desired.x) > tolerance || abs(current.y - desired.y) > tolerance

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
@Suppress("ReturnCount")
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

internal fun Editor.documentationTargetScreenBounds(
    startOffset: Int,
    endOffset: Int,
): Rectangle {
    val start = offsetToXY(startOffset)
    val end = offsetToXY(endOffset)
    val width = if (start.y == end.y) (end.x - start.x).coerceAtLeast(1) else lineHeight
    SwingUtilities.convertPointToScreen(start, contentComponent)
    return Rectangle(start.x, start.y, width, lineHeight)
}

internal fun taigaHoverPopupPlacementForScreen(
    target: Rectangle,
    size: Dimension,
): TaigaHoverPopupPlacement {
    val center = Point(target.x + target.width / 2, target.y + target.height / 2)
    val screen = ScreenUtil.getScreenRectangle(center)
    return calculateTaigaHoverPopupPlacement(target, size, screen, JBUI.scale(12))
}

internal fun positionTaigaHoverCard(
    popup: JBPopup,
    panel: TaigaQuickDocumentationPopupPanel,
    target: Rectangle,
) {
    if (!popup.isVisible) return
    val placement = taigaHoverPopupPlacementForScreen(target, popup.size)
    if (shouldRepositionTaigaHoverCard(popup.locationOnScreen, placement.location, JBUI.scale(3))) {
        popup.setLocation(placement.location)
        popup.moveToFitScreen()
    }
    panel.alignHoverPointer(
        target.y + target.height / 2 - popup.locationOnScreen.y,
        placement.pointerSide,
    )
}

/** Position the popup after its real size is known and keep pinned/explicit geometry unchanged. */
@Suppress("LongParameterList")
internal fun JBPopup.showTaigaDocumentationPopup(
    panel: TaigaQuickDocumentationPopupPanel,
    editor: Editor,
    target: Rectangle,
    initialPlacement: TaigaHoverPopupPlacement,
    pinnedSize: Dimension?,
    pinnedLocation: Point?,
    pinned: Boolean,
    explicit: Boolean,
) {
    if (pinned) pinnedSize?.let(::setSize)
    showInScreenCoordinates(editor.contentComponent, initialPlacement.location)
    if (pinned) {
        pinnedLocation?.let(::setLocation)
        moveToFitScreen()
    } else if (explicit) {
        moveToFitScreen()
    } else {
        positionTaigaHoverCard(this, panel, target)
    }
}
