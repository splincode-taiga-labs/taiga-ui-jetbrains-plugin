package org.taigaui.designtokens.completion

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JList
import javax.swing.ListSelectionModel

class FolderColorPreviewDialogTest : BasePlatformTestCase() {
    fun testPreviewListsFilesAndSupportsSwitchingBetweenDiffs() {
        val css = myFixture.configureByText("card.css", ".card { color: #fff; }")
        val less = myFixture.configureByText("theme.less", ".theme { color: #000; }")
        val previews =
            listOf(
                FolderColorPreview(
                    "card.css",
                    css.fileType,
                    css.text,
                    ".card { color: var(--tui-white); }",
                ),
                FolderColorPreview(
                    "nested/theme.less",
                    less.fileType,
                    less.text,
                    ".theme { color: #000 /* Taiga UI: consider var(--tui-black) */; }",
                ),
            )
        val dialog = FolderColorPreviewDialog(project, previews)

        try {
            val files = dialog.getPreferredFocusedComponent() as JList<*>

            assertEquals(FOLDER_COLOR_REPLACEMENT_TITLE, dialog.title)
            assertEquals("Changed stylesheets", files.accessibleContext.accessibleName)
            assertEquals(ListSelectionModel.SINGLE_SELECTION, files.selectionMode)
            assertEquals(2, files.model.size)
            assertEquals("card.css", files.selectedValue)
            assertEquals("nested/theme.less", files.model.getElementAt(1))

            files.selectedIndex = 1
            assertEquals("nested/theme.less", files.selectedValue)

            files.clearSelection()
            assertNull(files.selectedValue)

            files.selectedIndex = 0
            assertEquals("card.css", files.selectedValue)
            assertEquals(previews.first().original, css.text)
            assertEquals(previews.last().original, less.text)
        } finally {
            dialog.close(1)
        }
    }
}
