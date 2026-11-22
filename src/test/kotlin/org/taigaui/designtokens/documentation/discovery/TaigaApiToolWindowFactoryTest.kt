package org.taigaui.designtokens.documentation.discovery

import com.intellij.ide.browsers.BrowserLauncher
import com.intellij.ide.browsers.BrowserLauncherAppless
import com.intellij.ide.browsers.WebBrowser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.UIUtil
import org.taigaui.designtokens.documentation.TaigaDocsCache
import org.taigaui.designtokens.documentation.TaigaDocsService
import org.taigaui.designtokens.documentation.TaigaDocsSources
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.tree.TreePath

class TaigaApiToolWindowFactoryTest : BasePlatformTestCase() {
    private lateinit var workspace: Path

    override fun setUp() {
        super.setUp()
        workspace = Files.createTempDirectory("taiga-api-tool-window")
    }

    override fun tearDown() {
        try {
            FileEditorManager.getInstance(project).let { manager ->
                manager.openFiles.forEach(manager::closeFile)
            }
            workspace.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testRegistersFocusableTabsAndShowsUnavailableDocumentation() {
        val panel = createApiPanel()
        val tree = panel.child<Tree>()

        await("unavailable documentation") {
            tree.emptyText.text == "Taiga UI documentation is unavailable for this project."
        }
        assertFalse(panel.button("Open Documentation").isEnabled)
        assertEquals("", panel.child<JBTextArea>().text)
        tree.actionMap.get("taiga.openApiDocumentation").actionPerformed(ActionEvent(tree, 0, "enter"))

        panel.child<SearchTextField>().text = "input"
        panel.child<SearchTextField>().textEditor.postActionEvent()
        panel.button("Reload").doClick()

        await("unavailable documentation after reload") {
            tree.emptyText.text == "Taiga UI documentation is unavailable for this project."
        }
        assertNull(tree.selectionPath)
    }

    fun testSearchSelectsMatchingEntryAndClearsDetailsForNoMatches() {
        val cache = TaigaDocsCache()
        val source = requireNotNull(TaigaDocsSources.forMajor(5))
        val previousContent = cache.read(source)
        val docs = project.service<TaigaDocsService>()

        try {
            assertTrue(cache.write(source, DOCUMENTATION))
            docs.clearMemory()
            openApplication()

            val panel = createApiPanel()
            val search = panel.child<SearchTextField>()
            val tree = panel.child<Tree>()
            val details = panel.child<JBTextArea>()
            val open = panel.button("Open Documentation")

            await("API categories") { tree.model.getChildCount(tree.model.root) == 2 }
            assertTrue(panel.descendants().filterIsInstance<JLabel>().any { it.text == "Taiga UI 5.18.0" })

            val root = tree.model.root
            val category = tree.model.getChild(root, 0)
            val categoryPath = TreePath(arrayOf(root, category))

            tree.selectionPath = categoryPath
            assertFalse(open.isEnabled)
            assertEquals("", details.text)

            search.text = "input"
            await("automatically selected result") { tree.selectionPath?.pathCount == 3 && open.isEnabled }
            assertTrue(details.text.contains("@taiga-ui/core"))
            val renderer =
                tree.cellRenderer.getTreeCellRendererComponent(
                    tree,
                    tree.lastSelectedPathComponent,
                    true,
                    false,
                    true,
                    1,
                    false,
                ) as SimpleColoredComponent

            assertEquals("Input  @taiga-ui/core", renderer.getCharSequence(false).toString())
            search.textEditor.postActionEvent()
            assertOpensSelectedEntry(tree, open)

            search.text = "a-nonexistent-api"
            await("no matching API") {
                tree.emptyText.text == "No matching Taiga UI entries" && tree.model.getChildCount(tree.model.root) == 0
            }
            assertNull(tree.selectionPath)
            assertEquals("", details.text)
            assertFalse(open.isEnabled)

            search.text = ""
            await("unfiltered categories") { tree.model.getChildCount(tree.model.root) == 2 }
            assertNull(tree.selectionPath)
        } finally {
            docs.clearMemory()
            if (previousContent == null) {
                cache.invalidate(source)
            } else {
                cache.write(source, previousContent)
            }
        }
    }

    private fun assertOpensSelectedEntry(
        tree: Tree,
        open: JButton,
    ) {
        val openedUrls = mutableListOf<String>()
        val browserLauncher =
            object : BrowserLauncherAppless() {
                override fun browse(
                    url: String,
                    browser: WebBrowser?,
                    project: Project?,
                ) {
                    openedUrls.add(url)
                }
            }
        ApplicationManager
            .getApplication()
            .replaceService(BrowserLauncher::class.java, browserLauncher, testRootDisposable)

        open.doClick()
        tree.actionMap.get("taiga.openApiDocumentation").actionPerformed(ActionEvent(tree, 0, "enter"))
        val row = requireNotNull(tree.getPathBounds(tree.selectionPath))

        clickTree(tree, row.x + row.width / 2, row.y + row.height / 2, 1)
        clickTree(tree, row.x + row.width / 2, row.y + row.height / 2, 2)
        clickTree(tree, 0, row.y + 1_000, 2)

        assertEquals(List(3) { "https://taiga-ui.dev/components/input" }, openedUrls)
    }

    private fun clickTree(
        tree: Tree,
        x: Int,
        y: Int,
        clicks: Int,
    ) {
        tree.dispatchEvent(
            MouseEvent(tree, MouseEvent.MOUSE_CLICKED, 0, 0, x, y, clicks, false, MouseEvent.BUTTON1),
        )
    }

    private fun createApiPanel(): Container {
        val manager = ContentFactory.getInstance().createContentManager(false, project)
        Disposer.register(testRootDisposable, manager)
        val toolWindow =
            Proxy.newProxyInstance(
                ToolWindow::class.java.classLoader,
                arrayOf(ToolWindow::class.java),
            ) { _, method, _ ->
                check(method.name == "getContentManager")
                manager
            } as ToolWindow

        TaigaApiToolWindowFactory().createToolWindowContent(project, toolWindow)

        assertEquals(listOf("API", "Tokens"), manager.contents.map { it.displayName })
        manager.contents.forEach { tab ->
            assertSame(tab.component.child<SearchTextField>().textEditor, tab.preferredFocusableComponent)
        }

        return manager.contents.first().component
    }

    private fun openApplication() {
        val packageRoot = Files.createDirectories(workspace.resolve("node_modules/@taiga-ui/core"))
        Files.writeString(packageRoot.resolve("package.json"), """{"name":"@taiga-ui/core","version":"5.18.0"}""")
        val source = Files.writeString(workspace.resolve("component.ts"), "export class Example {}")
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source))

        myFixture.openFileInEditor(file)
    }

    private fun Container.button(label: String): JButton =
        descendants().filterIsInstance<JButton>().single { it.text == label }

    private inline fun <reified T : Component> Container.child(): T = descendants().filterIsInstance<T>().single()

    private companion object {
        val DOCUMENTATION =
            """
            # components/Input
            - **Package**: CORE

            # directives/Appearance
            - **Package**: CORE
            """.trimIndent()
    }
}

internal fun Container.descendants(): Sequence<Component> =
    components.asSequence().flatMap { child ->
        if (child is Container) sequenceOf(child) + child.descendants() else sequenceOf(child)
    }

internal fun await(
    description: String,
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + 10_000_000_000L

    while (System.nanoTime() < deadline) {
        UIUtil.dispatchAllInvocationEvents()
        if (condition()) {
            return
        }

        Thread.sleep(10)
    }

    junit.framework.TestCase.assertTrue("Timed out waiting for $description", condition())
}
