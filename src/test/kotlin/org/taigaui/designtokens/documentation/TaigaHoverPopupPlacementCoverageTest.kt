package org.taigaui.designtokens.documentation

import junit.framework.TestCase
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle

class TaigaHoverPopupPlacementCoverageTest : TestCase() {
    fun testNarrowScreenPlacesCardAboveWhenThereIsNoRoomBelow() {
        val placement =
            calculateTaigaHoverPopupPlacement(
                target = Rectangle(240, 270, 80, 20),
                popup = Dimension(280, 160),
                screen = Rectangle(100, 100, 350, 210),
                gap = 12,
            )

        assertEquals(TaigaHoverPointerSide.NONE, placement.pointerSide)
        assertEquals(Point(170, 100), placement.location)
    }

    fun testOversizedCardStaysClampedToAvailableScreenOrigin() {
        val placement =
            calculateTaigaHoverPopupPlacement(
                target = Rectangle(40, 30, 20, 20),
                popup = Dimension(300, 180),
                screen = Rectangle(10, 20, 180, 100),
                gap = 8,
            )

        assertEquals(TaigaHoverPointerSide.NONE, placement.pointerSide)
        assertEquals(Point(10, 20), placement.location)
    }

    fun testRightPlacementClampsVerticalPositionWithinScreen() {
        val placement =
            calculateTaigaHoverPopupPlacement(
                target = Rectangle(130, 320, 40, 30),
                popup = Dimension(200, 180),
                screen = Rectangle(100, 100, 700, 300),
                gap = 12,
            )

        assertEquals(TaigaHoverPointerSide.LEFT, placement.pointerSide)
        assertEquals(Point(182, 220), placement.location)
    }

    fun testRepositionThresholdUsesStrictToleranceForBothAxes() {
        val initial = Point(100, 200)

        assertFalse(shouldRepositionTaigaHoverCard(initial, Point(104, 196), tolerance = 4))
        assertTrue(shouldRepositionTaigaHoverCard(initial, Point(105, 200), tolerance = 4))
        assertTrue(shouldRepositionTaigaHoverCard(initial, Point(100, 195), tolerance = 4))
    }
}
