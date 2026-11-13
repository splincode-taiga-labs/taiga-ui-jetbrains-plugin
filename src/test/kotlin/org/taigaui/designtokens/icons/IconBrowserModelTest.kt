package org.taigaui.designtokens.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconBrowserModelTest {
    private val names =
        listOf(
            "@tui.actions.close",
            "@tui.actions.search",
            "@tui.fancy.SearchLarge",
            "@tui.flags.estonia",
        )

    @Test
    fun `empty query returns original catalog order`() {
        assertEquals(names, filterIconNames(names, ""))
        assertEquals(names, filterIconNames(names, "   "))
    }

    @Test
    fun `search is case insensitive and ignores surrounding whitespace`() {
        assertEquals(
            listOf("@tui.actions.search", "@tui.fancy.SearchLarge"),
            filterIconNames(names, " SEARCH "),
        )
    }

    @Test
    fun `no matches returns empty list without introducing other catalog entries`() {
        assertTrue(filterIconNames(names, "not-an-icon").isEmpty())
        assertEquals(names, filterIconNames(names, ""))
    }

    @Test
    fun `all supported copy and insert formats use the exact selected icon`() {
        val name = "@tui.actions.search"

        assertEquals(name, IconBrowserSnippet.NAME.render(name))
        assertEquals(
            """<tui-icon icon="@tui.actions.search" />""",
            IconBrowserSnippet.ELEMENT.render(name),
        )
        assertEquals(
            """iconStart="@tui.actions.search"""",
            IconBrowserSnippet.ICON_START.render(name),
        )
    }

    @Test
    fun `format labels describe the snippet choice`() {
        assertEquals("Icon name", IconBrowserSnippet.NAME.toString())
        assertEquals("<tui-icon>", IconBrowserSnippet.ELEMENT.toString())
        assertEquals("iconStart", IconBrowserSnippet.ICON_START.toString())
    }
}
