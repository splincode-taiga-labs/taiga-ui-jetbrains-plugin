package org.taigaui.designtokens.completion

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndGet
import java.awt.Dimension
import java.awt.Point
import javax.swing.JLayeredPane

class PreviewLocationCoverageTest : BasePlatformTestCase() {
    fun testDesignTokenPreviewLocationUsesRightLeftAndStableAnchorBranches() {
        val lookup = activeLookup()
        val method =
            Class
                .forName(
                    "org.taigaui.designtokens.completion." +
                        "DesignTokenCompletionPreviewControllerKt",
                ).declaredMethods
                .single { candidate ->
                    candidate.name == "previewLocation" &&
                        candidate.parameterCount == 4
                }.apply { isAccessible = true }
        val largePane =
            JLayeredPane().apply {
                setSize(4_000, 2_000)
            }
        val preview = Dimension(240, 180)
        val right =
            method.invoke(
                null,
                lookup,
                preview,
                largePane,
                null,
            ) as Point

        assertTrue(right.x >= 0)
        assertTrue(right.y >= 0)

        val tinyPane =
            JLayeredPane().apply {
                setSize(1, 1)
            }
        val left =
            method.invoke(
                null,
                lookup,
                preview,
                tinyPane,
                123,
            ) as Point

        assertTrue(left.x >= 0)
        assertEquals(123, left.y)
    }

    fun testIconPreviewLocationUsesLeftAndRightFallbackBranches() {
        val lookup = activeLookup()
        val method =
            Class
                .forName(
                    "org.taigaui.designtokens.icons." +
                        "IconCompletionPreviewControllerKt",
                ).declaredMethods
                .single { candidate ->
                    candidate.name == "iconPreviewLocation" &&
                        candidate.parameterCount == 3
                }.apply { isAccessible = true }
        val preview = Dimension(180, 120)
        val largePane =
            JLayeredPane().apply {
                setSize(4_000, 2_000)
            }
        val left =
            method.invoke(
                null,
                lookup,
                preview,
                largePane,
            ) as Point

        assertTrue(left.x >= 0)
        assertTrue(left.y >= 0)

        val tinyPane =
            JLayeredPane().apply {
                setSize(1, 1)
            }
        val fallback =
            method.invoke(
                null,
                lookup,
                preview,
                tinyPane,
            ) as Point

        assertEquals(0, fallback.x)
        assertEquals(0, fallback.y)
    }

    private fun activeLookup(): Lookup {
        myFixture.configureByText(
            "preview.css",
            ".demo { color: <caret>; }",
        )
        myFixture.completeBasic()

        return requireNotNull(
            runInEdtAndGet {
                LookupManager.getActiveLookup(myFixture.editor)
            },
        )
    }
}
