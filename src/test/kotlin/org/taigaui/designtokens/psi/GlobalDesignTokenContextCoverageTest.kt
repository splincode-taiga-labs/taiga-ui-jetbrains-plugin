package org.taigaui.designtokens.psi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalDesignTokenContextCoverageTest {
    @Test
    fun `recognizes supported global selectors and mixins`() {
        listOf(
            listOf(":root"),
            listOf(":host"),
            listOf("html"),
            listOf("body"),
            listOf("&:root"),
            listOf("[tuiPlatform='ios']"),
            listOf("[data-platform=android]"),
            listOf("[tuiTheme=light]"),
            listOf(":root[tuiTheme=dark]"),
            listOf(".light()"),
            listOf(".dark()"),
            listOf(".tui-theme-light()"),
            listOf(".tui-theme-dark()"),
            listOf(".tui-theme-variables()"),
            listOf(":root, :host"),
            listOf(":root", "[tuiTheme=dark]"),
        ).forEach { selectors ->
            assertTrue("Expected global: $selectors", GlobalDesignTokenContext.isGlobal(selectors))
        }
    }

    @Test
    fun `rejects empty mixed and component-scoped selectors`() {
        assertFalse(GlobalDesignTokenContext.isGlobal(emptyList()))
        assertFalse(GlobalDesignTokenContext.isGlobal(listOf(".button")))
        assertFalse(GlobalDesignTokenContext.isGlobal(listOf(":root .button")))
        assertFalse(GlobalDesignTokenContext.isGlobal(listOf(":root, .button")))
        assertFalse(GlobalDesignTokenContext.isGlobal(listOf("")))
    }
}
