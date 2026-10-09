package org.taigaui.designtokens.documentation

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.labels.LinkLabel
import java.awt.datatransfer.DataFlavor
import javax.swing.JButton

class TaigaQuickDocumentationUxTest : TaigaDocumentationPopupTestCase() {
    fun testAllValuesAreSearchableAndStaleFilteringCannotEnableWrites() {
        val owner = directive()
        val values = (1..15).map { "value$it" }
        val type = values.joinToString(" | ") { "'$it'" }
        val member =
            owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT).copy(
                subject =
                    owner.subject.copy(
                        localDocumentation = TaigaLocalDocumentation(inputTypes = mapOf("size" to type)),
                    ),
                binding = TaigaDocumentationBinding("size", 0, 8, "size=\"s\"", 6, 7, "s", false),
            )
        var applied: String? = null
        val panel =
            TaigaQuickDocumentationPopupPanel(
                member,
                actions =
                    TaigaDocumentationPopupActions(applyValue = {
                        applied = it
                        "Applied $it"
                    }),
            )
        assertFalse(descendants(panel).filterIsInstance<JButton>().any { it.text == "Apply 'value15'" })
        val more = descendants(panel).filterIsInstance<JButton>().first { it.text == "Show all (15)" }
        activate(more, "ENTER")
        val search = descendants(panel).filterIsInstance<JBTextField>().single()
        assertTrue(search.isVisible)
        search.text = "value15"
        activate(descendants(panel).filterIsInstance<JButton>().first { it.text == "Apply 'value15'" }, "ENTER")
        assertEquals("value15", applied)
        panel.invalidateContext()
        search.text = "value14"
        val apply = descendants(panel).filterIsInstance<JButton>().first { it.text == "Apply 'value14'" }
        assertFalse(apply.isEnabled)
        activate(apply, "ENTER")
        activate(descendants(panel).filterIsInstance<JButton>().first { it.text == "Copy 'value14'" }, "ENTER")
        assertEquals("value15", applied)
        assertEquals("'value14'", CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor))
        search.text = "unknown"
        assertTrue(labelText(panel).contains("0 of 15 values"))
    }

    fun testCopyActionsKeepCompleteTypesImportsAndExamples() {
        val owner = directive()
        val code = "<button tuiButton>Save</button>\n".repeat(60)
        val entity = owner.copy(entity = owner.entity.copy(example = TaigaExample("html", code)))
        val panel = TaigaQuickDocumentationPopupPanel(entity, showExample = true)
        activate(descendants(panel).filterIsInstance<JButton>().first { it.text == "Copy example" }, "ENTER")
        assertEquals(code, CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor))
        activate(descendants(panel).filterIsInstance<JButton>().first { it.text == "Copy import" }, "ENTER")
        assertEquals(
            "import {TuiButton} from '@taiga-ui/core';",
            CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor),
        )
        val member = owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT)
        val inputPanel = TaigaQuickDocumentationPopupPanel(member)
        activate(descendants(inputPanel).filterIsInstance<JButton>().first { it.text == "Copy type" }, "ENTER")
        assertEquals("TuiSize", CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor))
    }

    fun testRefreshProgressBlocksWritesAndKeepsCopyAndImportHelp() {
        var refreshed = false
        var quickFixes = false
        val panel =
            TaigaQuickDocumentationPopupPanel(
                directive(),
                actions =
                    TaigaDocumentationPopupActions(
                        refresh = { refreshed = true },
                        showImportFixes = { quickFixes = true },
                    ),
            )
        panel.showRefreshProgress(true)
        assertTrue(labelText(panel).contains("Waiting for indexing"))
        assertFalse(descendants(panel).filterIsInstance<JButton>().first { it.text == "Refresh" }.isEnabled)
        descendants(panel)
            .filterIsInstance<LinkLabel<*>>()
            .first { it.text == "Angular quick fixes →" }
            .doClick()
        assertTrue(quickFixes)
        activate(descendants(panel).filterIsInstance<JButton>().first { it.text == "Copy import" }, "ENTER")
        val imported = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor).toString()
        assertTrue(imported.contains("TuiButton"))
        panel.showRefreshFailure()
        descendants(panel).filterIsInstance<JButton>().first { it.text == "Refresh" }.doClick()
        assertTrue(refreshed)
    }

    fun testUnavailableExplicitCardExplainsTheTargetAndOffersNativeQuickFixes() {
        var closed = false
        var fixes = false
        val panel = TaigaDocumentationStatusPanel("Waiting for indexing…", { closed = true }, { fixes = true })
        panel.showLoading()
        assertTrue(labelText(panel).contains("Loading"))
        panel.showUnavailable()
        assertTrue(labelText(panel).contains("No installed Taiga UI API"))
        val quickFixes = descendants(panel).filterIsInstance<JButton>().first { it.text == "Angular quick fixes" }
        assertTrue(quickFixes.isVisible)
        activate(quickFixes, "ENTER")
        assertTrue(fixes)
        activate(panel, "ESCAPE")
        assertTrue(closed)
    }

    fun testApiSelectionAndCompleteDescriptionAreAccessible() {
        val owner = directive()
        val description = "Long documentation. ".repeat(40) + "Full description ends here."
        val documentedOwner = owner.copy(entity = owner.entity.copy(description = description))
        val panel =
            TaigaQuickDocumentationPopupPanel(
                documentedOwner,
                apiQuery = "",
                selectedMember = "INPUT:iconEnd",
            )
        val list = descendants(panel).filterIsInstance<JBList<*>>().single()
        assertEquals("iconEnd", (list.selectedValue as TaigaDocumentationApiRow).property.name)
        assertFalse(labelText(panel).contains("Full description ends here."))
        descendants(panel).filterIsInstance<LinkLabel<*>>().first { it.text == "Show full description" }.doClick()
        assertTrue(labelText(panel).contains("Full description ends here."))
        renderAndSave(panel, "expanded-description")
    }

    fun testLongSharedBindingCardsFitLightAndDarkThemes() {
        val owner = directive()
        val type = "VeryLongInstalledInputType | ".repeat(30) + "string"
        val property =
            owner.entity.inputs
                .first()
                .copy(documentedType = type)
        val first =
            owner.focusedMember(owner.entity.inputs.first(), TaigaApiMemberKind.INPUT).copy(
                property = property,
            )
        val secondSubject = first.subject.copy(publicSymbol = "TuiOtherReceiver")
        val second = first.copy(subject = secondSubject)
        val shared = first.copy(receivers = listOf(first, second))
        val previousBright = JBColor.isBright()
        try {
            listOf(false, true).forEach { dark ->
                JBColor.setDark(dark)
                val panel =
                    TaigaQuickDocumentationPopupPanel(
                        shared,
                        actions = TaigaDocumentationPopupActions(showImportFixes = {}),
                    )
                assertTrue("Long types must fit the card", panel.preferredSize.width <= 580)
                val copies =
                    descendants(panel).filterIsInstance<JButton>().filter { it.text.startsWith("Copy ") }.toList()
                assertEquals(2, copies.size)
                copies.forEach { assertEquals(type, it.toolTipText) }
                renderAndSave(panel, if (dark) "long-receivers-dark" else "long-receivers-light")
            }
        } finally {
            JBColor.setDark(!previousBright)
        }
    }
}
