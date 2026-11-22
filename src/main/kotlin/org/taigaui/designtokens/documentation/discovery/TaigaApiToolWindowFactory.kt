package org.taigaui.designtokens.documentation.discovery

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.taigaui.designtokens.documentation.TaigaDocKind
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

internal class TaigaApiToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val panel = TaigaApiToolWindowPanel(project)
        val manager = toolWindow.contentManager
        val content = manager.factory.createContent(panel, "API", false)

        content.setDisposer(panel)
        content.setPreferredFocusableComponent(panel.searchField.textEditor)
        manager.addContent(content)

        val tokens = TaigaTokenExplorerPanel(project)
        val tokenContent = manager.factory.createContent(tokens, "Tokens", false)

        tokenContent.setDisposer(tokens)
        tokenContent.setPreferredFocusableComponent(tokens.searchField.textEditor)
        manager.addContent(tokenContent)
    }
}

private class TaigaApiToolWindowPanel(
    private val project: Project,
) : JPanel(BorderLayout()),
    Disposable {
    val searchField =
        SearchTextField(false).apply {
            textEditor.emptyText.text = "Search Taiga UI"
            textEditor.getAccessibleContext().accessibleName = "Search Taiga UI"
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private val versionLabel = JBLabel()
    private val tree =
        Tree(DefaultTreeModel(DefaultMutableTreeNode())).apply {
            isRootVisible = false
            showsRootHandles = true
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            cellRenderer = TaigaApiTreeRenderer()
            getAccessibleContext().accessibleName = "Taiga UI documentation tree"
        }
    private val details =
        JBTextArea(3, 0).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            getAccessibleContext().accessibleName = "Taiga UI API summary"
        }
    private val openButton = JButton("Open Documentation")
    private val reloadButton = JButton("Reload")
    private var source: Path? = null
    private var catalog: TaigaApiCatalog? = null
    private var loadJob: Job? = null
    private var filterJob: Job? = null

    init {
        val searchPanel =
            JPanel(BorderLayout(JBUI.scale(4), JBUI.scale(4))).apply {
                add(searchField, BorderLayout.CENTER)
                add(reloadButton, BorderLayout.EAST)
                add(versionLabel, BorderLayout.SOUTH)
                border = JBUI.Borders.empty(6)
            }
        val detailsPanel =
            JPanel(BorderLayout(JBUI.scale(4), JBUI.scale(4))).apply {
                add(JBScrollPane(details), BorderLayout.CENTER)
                add(openButton, BorderLayout.SOUTH)
                border = JBUI.Borders.empty(6)
            }

        add(searchPanel, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
        add(detailsPanel, BorderLayout.SOUTH)
        configureActions()

        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val next = taigaApiSource(project, event.newFile)

                    if (source != next) {
                        load(next)
                    }
                }
            },
        )
        reloadButton.addActionListener { load(taigaApiSource(project)) }
        load(taigaApiSource(project))
    }

    private fun configureActions() {
        searchField.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) = filter()
            },
        )
        searchField.textEditor.addActionListener {
            if (tree.selectionPath != null) {
                tree.requestFocusInWindow()
            }
        }
        tree.addTreeSelectionListener { showDetails() }
        openButton.addActionListener { openSelected() }
        tree.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "taiga.openApiDocumentation")
        tree.actionMap.put(
            "taiga.openApiDocumentation",
            object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) = openSelected()
            },
        )
        tree.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.clickCount == 2 && SwingUtilities.isLeftMouseButton(event)) {
                        val path = tree.getPathForLocation(event.x, event.y)
                        path?.lastPathComponent.toApiEntry()?.openDocumentation()
                    }
                }
            },
        )
    }

    private fun load(path: Path?) {
        source = path
        loadJob?.cancel()
        filterJob?.cancel()
        catalog = null
        versionLabel.text = ""
        render(emptyList(), "Loading Taiga UI documentation…")

        loadJob =
            scope.launch {
                catalog = path?.let { TaigaApiCatalog.load(project, it) }
                versionLabel.text = catalog?.let { "Taiga UI ${it.version}" }.orEmpty()

                if (catalog == null) {
                    render(emptyList(), "Taiga UI documentation is unavailable for this project.")
                } else {
                    filter()
                }
            }
    }

    private fun filter() {
        val current = catalog ?: return
        val query = searchField.text
        filterJob?.cancel()
        filterJob =
            scope.launch {
                val matches =
                    withContext(Dispatchers.Default) {
                        current.search(query) { ensureActive() }.map(TaigaApiMatch::entry)
                    }

                render(matches, if (query.isBlank()) "No Taiga UI entries" else "No matching Taiga UI entries")
            }
    }

    private fun render(
        entries: List<TaigaApiEntry>,
        emptyText: String,
    ) {
        val root = DefaultMutableTreeNode()
        val groups = entries.groupBy { it.entity.kind }
        val searching = searchField.text.isNotBlank()

        (if (searching) groups else groups.toSortedMap()).forEach { (kind, items) ->
            val category = DefaultMutableTreeNode(kind)
            items.forEach { category.add(DefaultMutableTreeNode(it, false)) }
            root.add(category)
        }

        tree.emptyText.text = emptyText
        tree.model = DefaultTreeModel(root)

        if (searching) {
            val categories = root.children()

            while (categories.hasMoreElements()) {
                tree.expandPath(TreePath(arrayOf(root, categories.nextElement())))
            }

            if (root.childCount > 0) {
                val firstGroup = root.firstChild
                tree.selectionPath = TreePath(arrayOf(root, firstGroup, firstGroup.getChildAt(0)))
            }
        }

        showDetails()
    }

    private fun selectedEntry(): TaigaApiEntry? = tree.lastSelectedPathComponent.toApiEntry()

    private fun showDetails() {
        val entry = selectedEntry()
        details.text = entry?.let { listOfNotNull(it.metadata, it.summary).joinToString("\n") }.orEmpty()
        details.caretPosition = 0
        openButton.isEnabled = entry != null
    }

    private fun openSelected() {
        selectedEntry()?.openDocumentation()
    }

    override fun dispose() = scope.cancel()
}

private class TaigaApiTreeRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
            is TaigaApiEntry -> {
                append(item.entity.title)
                item.packages.firstOrNull()?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }

            is TaigaDocKind -> {
                append(item.categoryLabel, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            }
        }
    }
}

internal fun TaigaApiEntry.openDocumentation() {
    BrowserUtil.browse(entity.documentationUri.toString())
}

private fun Any?.toApiEntry(): TaigaApiEntry? = (this as? DefaultMutableTreeNode)?.userObject as? TaigaApiEntry
