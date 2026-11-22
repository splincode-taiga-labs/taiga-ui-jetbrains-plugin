package org.taigaui.designtokens.documentation.discovery

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.taigaui.designtokens.documentation.DesignTokenHoverPopupModel
import org.taigaui.designtokens.documentation.DesignTokenNavigationTarget
import org.taigaui.designtokens.documentation.createDesignTokenReferenceAccordion
import org.taigaui.designtokens.documentation.createDesignTokenValueSections
import org.taigaui.designtokens.documentation.navigate
import org.taigaui.designtokens.project.DesignTokenCatalogEntry
import org.taigaui.designtokens.project.DesignTokenIndexService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.HierarchyEvent
import java.io.IOException
import java.nio.file.Path
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

internal class TaigaTokenExplorerPanel(
    private val project: Project,
) : JPanel(BorderLayout()),
    Disposable {
    val searchField =
        SearchTextField(false).apply {
            textEditor.emptyText.text = "Search design tokens"
            textEditor.getAccessibleContext().accessibleName = "Search design tokens"
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private val tokenList =
        JBList<DesignTokenCatalogEntry>().apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = TokenExplorerCellRenderer()
            getAccessibleContext().accessibleName = "Taiga UI design tokens"
        }
    private val reloadButton = JButton("Reload")
    private val copyButton = JButton("Copy var()").apply { isEnabled = false }
    private val details = JBScrollPane().apply { verticalScrollBar.unitIncrement = JBUI.scale(16) }
    private var source: Path? = null
    private var catalog: TaigaTokenExplorerCatalog? = null
    private var loadJob: Job? = null
    private var filterJob: Job? = null
    private var detailsJob: Job? = null

    init {
        composePanel()
        configureActions()
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val next = taigaApiSource(project, event.newFile)

                    if (isShowing && source != next) {
                        load(next)
                    }
                }
            },
        )
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) {
                load(taigaApiSource(project))
            }
        }
    }

    private fun composePanel() {
        val searchPanel =
            JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                border = JBUI.Borders.empty(6)
                add(searchField, BorderLayout.CENTER)
                add(reloadButton, BorderLayout.EAST)
            }
        val detailsPanel =
            JPanel(BorderLayout()).apply {
                add(details, BorderLayout.CENTER)
                add(
                    JPanel(FlowLayout(FlowLayout.TRAILING)).apply { add(copyButton) },
                    BorderLayout.SOUTH,
                )
            }

        add(searchPanel, BorderLayout.NORTH)
        add(
            OnePixelSplitter(true, 0.45f).apply {
                firstComponent = JBScrollPane(tokenList)
                secondComponent = detailsPanel
            },
            BorderLayout.CENTER,
        )
    }

    private fun configureActions() {
        searchField.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) = filter()
            },
        )
        searchField.textEditor.addActionListener {
            if (tokenList.selectedIndex >= 0) {
                tokenList.requestFocusInWindow()
            }
        }
        tokenList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                showSelectedToken()
            }
        }
        reloadButton.addActionListener { load(taigaApiSource(project)) }
        copyButton.addActionListener {
            tokenList.selectedValue?.let { entry ->
                CopyPasteManager.getInstance().setContents(StringSelection("var(${entry.name})"))
            }
        }
    }

    private fun load(path: Path?) {
        val selectedName = tokenList.selectedValue?.name

        source = path
        loadJob?.cancel()
        filterJob?.cancel()
        catalog = null
        tokenList.emptyText.text = "Loading design tokens…"
        tokenList.setListData(emptyArray())
        showSelectedToken()

        loadJob =
            scope.launch {
                try {
                    catalog =
                        path?.let { sourceFile ->
                            withContext(Dispatchers.IO) {
                                TaigaTokenExplorerCatalog.load(project.service<DesignTokenIndexService>(), sourceFile)
                            }
                        }
                    tokenList.emptyText.text = "No Taiga UI design tokens found"
                    filter(selectedName)
                } catch (error: IOException) {
                    LOG.warn("Failed to load Taiga UI design tokens", error)
                    tokenList.emptyText.text = "Could not load Taiga UI design tokens"
                }
            }
    }

    private fun filter(selectedName: String? = tokenList.selectedValue?.name) {
        val current = catalog ?: return
        val query = searchField.text

        filterJob?.cancel()
        filterJob =
            scope.launch {
                val entries = withContext(Dispatchers.Default) { current.search(query).toTypedArray() }
                val index = entries.indexOfFirst { entry -> entry.name == selectedName }

                tokenList.emptyText.text =
                    if (query.isBlank()) "No Taiga UI design tokens found" else "No matching design tokens"
                tokenList.setListData(entries)

                if (entries.isNotEmpty()) {
                    tokenList.selectedIndex = index.coerceAtLeast(0)
                    tokenList.ensureIndexIsVisible(tokenList.selectedIndex)
                }
            }
    }

    private fun showSelectedToken() {
        val current = catalog
        val entry = tokenList.selectedValue

        detailsJob?.cancel()
        copyButton.isEnabled = entry != null
        details.setViewportView(null)

        if (current != null && entry != null) {
            details.setViewportView(JBLabel("Resolving token…").apply { border = JBUI.Borders.empty(8) })
            detailsJob =
                scope.launch {
                    val model = withContext(Dispatchers.Default) { current.details(entry) }

                    details.setViewportView(tokenDetails(model) { target -> target.navigate(project) })
                }
        }
    }

    override fun dispose() = scope.cancel()

    private companion object {
        val LOG = Logger.getInstance(TaigaTokenExplorerPanel::class.java)
    }
}

private class TokenExplorerCellRenderer : ColoredListCellRenderer<DesignTokenCatalogEntry>() {
    override fun customizeCellRenderer(
        list: JList<out DesignTokenCatalogEntry>,
        value: DesignTokenCatalogEntry,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        append(value.name)
        append("  ${value.sourceLabel}", SimpleTextAttributes.GRAYED_ATTRIBUTES)

        if (value.deprecation != null) {
            append("  deprecated", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}

private fun tokenDetails(
    model: DesignTokenHoverPopupModel,
    navigate: (DesignTokenNavigationTarget) -> Unit,
): JComponent =
    JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)

        if (model.sections.isEmpty()) {
            add(JBLabel("No reachable declaration in this application."))
        } else {
            add(createDesignTokenValueSections(model.sections, 180, navigate))

            if (model.referenceChainCount > 0) {
                add(Box.createVerticalStrut(JBUI.scale(8)))
                add(createDesignTokenReferenceAccordion(model.sections, 300) {})
            }
        }
    }
