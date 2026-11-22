package org.taigaui.designtokens.documentation.discovery

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBList
import org.taigaui.designtokens.project.DesignTokenCatalogEntry
import org.taigaui.designtokens.project.DesignTokenIndexService
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JLabel

class TaigaTokenExplorerPanelTest : BasePlatformTestCase() {
    private lateinit var workspace: Path

    override fun setUp() {
        super.setUp()
        workspace = Files.createTempDirectory("taiga-token-explorer")
    }

    override fun tearDown() {
        try {
            FileEditorManager.getInstance(project).let { manager ->
                manager.openFiles.forEach(manager::closeFile)
            }
            project.getService(DesignTokenIndexService::class.java).clear()
            workspace.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testFiltersTokensRetainsSelectionAndCopiesSelectedReference() {
        myFixture.openFileInEditor(
            createApp(
                "legacy",
                """
                :root {
                    --tui-fixture-base: #aabbcc;
                    /** @deprecated Use --tui-fixture-base */
                    --tui-fixture-old: var(--tui-fixture-base);
                }
                """.trimIndent(),
            ),
        )
        val panel = createPanel()
        val tokens = panel.tokenList()
        val copy = panel.button("Copy var()")

        assertFalse(copy.isEnabled)
        panel.searchField.textEditor.postActionEvent()

        panel.button("Reload").doClick()
        await("initial token list") { tokens.model.size == 2 }
        assertEquals("--tui-fixture-base", tokens.selectedValue.name)
        await("base token details") { panel.labels().any { it.text == "Project styles" } }

        tokens.selectedIndex = 1
        panel.searchField.textEditor.postActionEvent()
        await("alias reference chain") {
            panel.descendants().filterIsInstance<ActionLink>().any { it.text == "▸ Reference chain (1)" }
        }
        val rendered =
            tokens.cellRenderer.getListCellRendererComponent(tokens, tokens.selectedValue, 1, true, false)
                as SimpleColoredComponent

        assertTrue(rendered.getCharSequence(false).contains("deprecated"))
        assertTrue(copy.isEnabled)
        val clipboard = CopyPasteManager.getInstance()
        val previousContent = clipboard.contents

        try {
            copy.doClick()
            assertEquals("var(--tui-fixture-old)", clipboard.getContents<String>(DataFlavor.stringFlavor))
        } finally {
            clipboard.setContents(previousContent ?: StringSelection(""))
        }

        panel.searchField.text = "  FIXTURE-OLD  "
        await("filtered selection") { tokens.model.size == 1 }
        assertEquals("--tui-fixture-old", tokens.selectedValue.name)

        panel.searchField.text = ""
        await("restored token list") { tokens.model.size == 2 }
        assertEquals("--tui-fixture-old", tokens.selectedValue.name)

        panel.searchField.text = "missing"
        await("no matching tokens") { tokens.emptyText.text == "No matching design tokens" && tokens.model.size == 0 }
        assertFalse(copy.isEnabled)
    }

    fun testVisibleExplorerReloadsForTheActiveApplication() {
        val legacy = createApp("legacy", ":root { --tui-fixture-legacy: red; }")
        val current = createApp("current", ":root { --tui-fixture-current: blue; }")

        myFixture.openFileInEditor(legacy)
        val panel = createPanel()
        val tokens = panel.tokenList()

        panel.addNotify()
        try {
            await("legacy token") { tokens.selectedValue?.name == "--tui-fixture-legacy" }
            myFixture.openFileInEditor(current)
            await("current token") { tokens.selectedValue?.name == "--tui-fixture-current" }
        } finally {
            panel.removeNotify()
        }
    }

    private fun createApp(
        name: String,
        styles: String,
    ): VirtualFile {
        val root = Files.createDirectories(workspace.resolve(name))
        val source = Files.writeString(root.resolve("component.ts"), "export class Example {}")

        Files.writeString(root.resolve("package.json"), """{"name":"$name"}""")
        Files.writeString(root.resolve("styles.less"), styles)

        return requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source))
    }

    private fun createPanel(): TaigaTokenExplorerPanel =
        TaigaTokenExplorerPanel(project).also { Disposer.register(testRootDisposable, it) }

    private fun TaigaTokenExplorerPanel.button(label: String): JButton =
        descendants().filterIsInstance<JButton>().single { it.text == label }

    private fun TaigaTokenExplorerPanel.labels(): Sequence<JLabel> = descendants().filterIsInstance<JLabel>()

    @Suppress("UNCHECKED_CAST")
    private fun TaigaTokenExplorerPanel.tokenList(): JBList<DesignTokenCatalogEntry> =
        (
            descendants().filterIsInstance<JBList<*>>().single {
                it.accessibleContext.accessibleName == "Taiga UI design tokens"
            }
        ) as JBList<DesignTokenCatalogEntry>
}
