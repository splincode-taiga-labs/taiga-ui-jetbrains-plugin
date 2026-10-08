package org.taigaui.designtokens.documentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Point

class DesignTokenReferenceHitTesterTest {
    @Test
    fun `accepts a pointer inside the token glyph bounds`() {
        assertTrue(
            DesignTokenReferenceHitTester.contains(
                start = Point(100, 40),
                end = Point(240, 40),
                lineHeight = 20,
                pointer = Point(180, 50),
            ),
        )
    }

    @Test
    fun `rejects a pointer in blank space after the token`() {
        assertFalse(
            DesignTokenReferenceHitTester.contains(
                start = Point(100, 40),
                end = Point(240, 40),
                lineHeight = 20,
                pointer = Point(500, 50),
            ),
        )
    }

    @Test
    fun `rejects end and bottom boundaries and multi line references`() {
        assertFalse(
            DesignTokenReferenceHitTester.contains(
                start = Point(100, 40),
                end = Point(240, 40),
                lineHeight = 20,
                pointer = Point(240, 50),
            ),
        )
        assertFalse(
            DesignTokenReferenceHitTester.contains(
                start = Point(100, 40),
                end = Point(240, 40),
                lineHeight = 20,
                pointer = Point(180, 60),
            ),
        )
        assertFalse(
            DesignTokenReferenceHitTester.contains(
                start = Point(100, 40),
                end = Point(240, 60),
                lineHeight = 20,
                pointer = Point(180, 50),
            ),
        )
    }

    @Test
    fun `rejects a pointer on another visual line`() {
        assertFalse(
            DesignTokenReferenceHitTester.contains(
                start = Point(100, 40),
                end = Point(240, 40),
                lineHeight = 20,
                pointer = Point(180, 70),
            ),
        )
    }
}
