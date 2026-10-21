package org.taigaui.designtokens.documentation

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TaigaDocumentationUsageTest : BasePlatformTestCase() {
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
            """<a tuiAvatar (loaded)="handler(${'
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
