package org.taigaui.designtokens.documentation

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel

class TaigaQuickDocumentationUsageTest : BasePlatformTestCase() {
    fun testUsageCodeBlockPreservesMarkupWithoutReformatting() {
        val source = "<button tuiButton size=\"m\" appearance=\"primary\">Save</button>"
        val codeBlock = documentationCodeBlock(source)
        val label = codeBlock.components.filterIsInstance<JBLabel>().single()

        assertEquals(source, label.accessibleContext.accessibleName)
    }

    fun testUsageChoosesOnlyTheSelectorMatchingTheActualHostTag() {
        val selectors = "tui-avatar,button[tuiAvatar],a[tuiAvatar]"

        assertEquals(
            """<tui-avatar [size]="value">...</tui-avatar>""",
            documentationMemberUsage("tui-avatar", selectors, "size", TaigaApiMemberKind.INPUT),
        )
        assertEquals(
            """<button tuiAvatar [size]="value">...</button>""",
            documentationMemberUsage("button", selectors, "size", TaigaApiMemberKind.INPUT),
        )
        assertEquals(
            "<a tuiAvatar (loaded)=\"handler(\$event)\">...</a>",
            documentationMemberUsage("a", selectors, "loaded", TaigaApiMemberKind.OUTPUT),
        )
        assertEquals(
            """<div [size]="value">...</div>""",
            documentationMemberUsage("div", selectors, "size", TaigaApiMemberKind.INPUT),
        )
    }

    fun testUsageKeepsTheDirectiveAttributeWithoutDuplicatingTheTagName() {
        assertEquals(
            """<button tuiButton [size]="value">...</button>""",
            documentationMemberUsage(
                "button",
                "a[tuiButton],button[tuiButton],label[tuiButton]",
                "size",
                TaigaApiMemberKind.INPUT,
            ),
        )
        assertEquals(
            """<button tuiButton [size]="value">...</button>""",
            documentationMemberUsage("button", "tuiButton", "size", TaigaApiMemberKind.INPUT),
        )
    }
}
