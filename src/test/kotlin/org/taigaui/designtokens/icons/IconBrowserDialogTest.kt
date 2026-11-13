package org.taigaui.designtokens.icons

import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel

class IconBrowserDialogTest : BasePlatformTestCase() {
    private lateinit var workspaceRoot: Path

    override fun setUp() {
        super.setUp()
        workspaceRoot = Files.createTempDirectory("taiga-icon-browser")
    }

    override fun tearDown() {
        try {
            workspaceRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testSearchSelectionCopyAndSourceDescription() {
        val source = openEditorWithIcons()
        val dialog = IconBrowserDialog(project, source)

        try {
            val list = field<JList<String>>(dialog, "list")
            val search = field<JBTextField>(dialog, "search")
            val status = field<JBLabel>(dialog, "status")
            val sourceInfo = field<JBLabel>(dialog, "sourceInfo")
            val format = field<JComboBox<IconBrowserSnippet>>(dialog, "format")
            val copy = field<JButton>(dialog, "copyButton")
            val insert = field<JButton>(dialog, "insertButton")

            awaitUi { list.model.size == 2 }
            assertEquals("2 of 2 icons", status.text)
            assertEquals("Search Taiga UI icons", search.accessibleContext.accessibleName)
            assertEquals("Taiga UI icon grid", list.accessibleContext.accessibleName)
            assertFalse(copy.isEnabled)
            assertFalse(insert.isEnabled)

            search.text = " ARROW "
            assertEquals(1, list.model.size)
            assertEquals("@tui.actions.arrow", list.model.getElementAt(0))
            assertEquals("1 of 2 icons", status.text)

            list.selectedIndex = 0
            assertTrue(copy.isEnabled)
            assertTrue(insert.isEnabled)
            assertEquals("Source: installed Taiga UI package", sourceInfo.text)

            format.selectedItem = IconBrowserSnippet.ELEMENT
            copy.doClick()
            assertEquals(
                """<tui-icon icon="@tui.actions.arrow" />""",
                CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor),
            )

            search.text = "missing"
            assertEquals(0, list.model.size)
            assertEquals("No icons match the search.", status.text)
            assertFalse(copy.isEnabled)
            assertFalse(insert.isEnabled)
            assertEquals("Select an icon to see its source", sourceInfo.text)
        } finally {
            dialog.close(1)
        }
    }

    fun testInsertUsesSelectedFormatAndPreservesEditorText() {
        val source = openEditorWithIcons()
        val editor = myFixture.editor
        val dialog = IconBrowserDialog(project, source)

        try {
            val list = field<JList<String>>(dialog, "list")
            val format = field<JComboBox<IconBrowserSnippet>>(dialog, "format")
            val insert = field<JButton>(dialog, "insertButton")

            awaitUi { list.model.size == 2 }
            list.selectedIndex = 0
            format.selectedItem = IconBrowserSnippet.ICON_START
            editor.caretModel.moveToOffset(editor.document.text.indexOf("</div>"))

            assertTrue(insert.isEnabled)
            insert.doClick()

            assertEquals(
                """<div>iconStart="@tui.actions.arrow"</div>""",
                editor.document.text,
            )
        } finally {
            dialog.close(1)
        }
    }

    fun testCellRendererHandlesLoadingFailedAndAvailablePreviews() {
        val source = openEditorWithIcons()
        val dialog = IconBrowserDialog(project, source)

        try {
            val list = field<JList<String>>(dialog, "list")
            val previews = field<MutableMap<String, javax.swing.Icon>>(dialog, "previews")
            val completed = field<MutableSet<String>>(dialog, "completedPreviews")
            val name = "@tui.actions.arrow"

            awaitUi { list.model.size == 2 }
            val renderer = list.cellRenderer

            fun render(selected: Boolean): Pair<JBLabel, JBLabel> {
                val cell =
                    renderer.getListCellRendererComponent(list, name, 0, selected, false) as JPanel
                assertEquals(name, cell.accessibleContext.accessibleName)
                val image = cell.getComponent(0) as JBLabel
                val caption =
                    (cell.layout as BorderLayout).getLayoutComponent(BorderLayout.SOUTH) as JBLabel
                assertEquals("actions.arrow", caption.text)
                assertEquals(name, caption.toolTipText)
                return image to caption
            }

            assertEquals("…", render(false).first.text)

            completed.add(name)
            assertEquals("Preview unavailable", render(false).first.text)

            previews[name] = ImageIcon(BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB))
            val image = render(true).first
            assertEquals("", image.text)
            assertNotNull(image.icon)
            assertEquals(list.selectionBackground, (image.parent as JPanel).background)
        } finally {
            dialog.close(1)
        }
    }

    private fun openEditorWithIcons(): Path {
        val icons = workspaceRoot.resolve("node_modules/@taiga-ui/icons/src/actions")
        Files.createDirectories(icons)
        Files.writeString(
            icons.resolve("arrow.svg"),
            """<svg viewBox="0 0 24 24"><path d="M0 0h24"/></svg>""",
        )
        Files.writeString(
            icons.resolve("check.svg"),
            """<svg viewBox="0 0 24 24"><path d="M0 0h24"/></svg>""",
        )

        val source = workspaceRoot.resolve("src/example.html")
        Files.createDirectories(source.parent)
        Files.writeString(source, "<div></div>")
        val virtualFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source))
        myFixture.configureFromExistingVirtualFile(virtualFile)

        assertEquals(
            listOf("@tui.actions.arrow", "@tui.actions.check"),
            project.service<IconCompletionService>().loadNow(source),
        )
        return source
    }

    private fun awaitUi(condition: () -> Boolean) {
        repeat(300) {
            UIUtil.dispatchAllInvocationEvents()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Icon browser UI did not receive catalog contents")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(
        target: Any,
        name: String,
    ): T =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T
}
