package org.taigaui.designtokens.documentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaigaTemplateSelectorAtOffsetTest {
    @Test
    fun findsAttributeDirective() {
        val html = "<button tuiButton size=\"m\">Save</button>"

        assertEquals(
            "tuiButton",
            TaigaTemplateSelectorAtOffset.find(html, html.indexOf("tuiButton") + 3),
        )
    }

    @Test
    fun findsAngularBoundDirective() {
        val html = "<button [tuiButton]=\"appearance\">Save</button>"

        assertEquals(
            "tuiButton",
            TaigaTemplateSelectorAtOffset.find(html, html.indexOf("tuiButton") + 4),
        )
    }

    @Test
    fun findsElementComponent() {
        val html = "<tui-calendar></tui-calendar>"

        assertEquals(
            "tui-calendar",
            TaigaTemplateSelectorAtOffset.find(html, html.indexOf("tui-calendar") + 4),
        )
    }

    @Test
    fun ignoresTaigaLikeTextInsideAttributeValue() {
        val html = "<div title=\"use tuiButton here\"></div>"

        assertNull(
            TaigaTemplateSelectorAtOffset.find(html, html.indexOf("tuiButton") + 2),
        )
    }

    @Test
    fun recognizesSelectorAndPublicSymbolDocumentationKeys() {
        assertTrue(TaigaTemplateSelectorAtOffset.isTaigaDocumentationKey("tuiButton"))
        assertTrue(TaigaTemplateSelectorAtOffset.isTaigaDocumentationKey("tui-calendar"))
        assertTrue(TaigaTemplateSelectorAtOffset.isTaigaDocumentationKey("TuiButton"))
        assertFalse(TaigaTemplateSelectorAtOffset.isTaigaDocumentationKey("Button"))
    }

    @Test
    fun ignoresTaigaLikeTextInsideUnquotedAttributeValue() {
        val html = "<div title=tuiButton></div>"

        assertNull(
            TaigaTemplateSelectorAtOffset.find(html, html.indexOf("tuiButton") + 2),
        )
    }

    @Test
    fun ignoresNonTaigaElementsAndAttributes() {
        val html = "<button class=\"primary\">Save</button>"

        assertNull(
            TaigaTemplateSelectorAtOffset.find(html, html.indexOf("button") + 2),
        )
        assertNull(
            TaigaTemplateSelectorAtOffset.find(html, html.indexOf("class") + 2),
        )
    }
}
