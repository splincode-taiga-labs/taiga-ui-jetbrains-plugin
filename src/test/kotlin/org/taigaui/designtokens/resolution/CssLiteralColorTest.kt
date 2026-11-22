package org.taigaui.designtokens.resolution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CssLiteralColorTest {
    @Test
    fun `normalizes hex and both RGB syntaxes without quantizing channels`() {
        val white = CssLiteralColor.parse("#fff")

        assertEquals(white, CssLiteralColor.parse("#ffffffff"))
        assertEquals(white, CssLiteralColor.parse("rgb(255 255 255)"))
        assertEquals(white, CssLiteralColor.parse("RGBA(100%, 100%, 100%, 1)"))
        assertEquals(CssLiteralColor.parse("#ff000066"), CssLiteralColor.parse("rgb(255 0 0 / 40%)"))
        assertEquals(CssLiteralColor.parse("rgba(255, 0, 0, .5)"), CssLiteralColor.parse("rgb(255 0 0 / 50%)"))
        assertNotEquals(CssLiteralColor.parse("#ff000080"), CssLiteralColor.parse("rgb(255 0 0 / .5)"))
        assertNotEquals(white, CssLiteralColor.parse("rgb(254.999 255 255)"))
    }

    @Test
    fun `rejects contextual colors and malformed or out of range channels`() {
        listOf(
            "rgb(from #fff r g b)",
            "rgb(255 255 255 / var(--opacity))",
            "rgb(255, 255, 100%)",
            "rgb(255 255 255 /)",
            "rgb(256 255 255)",
            "rgb(255 255 255 / 150%)",
            "linear-gradient(#fff, #000)",
        ).forEach { text -> assertNull(text, CssLiteralColor.parse(text)) }
    }
}
