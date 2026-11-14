package org.taigaui.designtokens.icons

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.taigaui.designtokens.cache.RefreshCallback
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import javax.swing.Action
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent

internal class IconBrowserDialog(
    private val project: Project,
    private val sourceFile: Path,
) : DialogWrapper(project, true) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private val renderer = IconSvgPreviewRenderer()
    private val model = DefaultListModel<String>()
    private val previews = mutableMapOf<String, javax.swing.Icon>()
    private val completedPreviews = mutableSetOf<String>()
    private var catalog: IconCatalog? = null
    private var loadingJob: Job? = null
    private var previewJob: Job? = null

    private val search = JBTextField()
    private val status = JBLabel("Loading Taiga UI icons...")
    private val sourceInfo = JBLabel("Select an icon to see its source")
    private val format = JComboBox(IconBrowserSnippet.entries.toTypedArray())
    private val copyButton = JButton("Copy")
    private val insertButton = JButton("Insert")
    private val list =
        JList(model).apply {
            layoutOrientation = JList.HORIZONTAL_WRAP
            visibleRowCount = -1
            fixedCellWidth = JBUI.scale(CELL_WIDTH)
            fixedCellHeight = JBUI.scale(CELL_HEIGHT)
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer =
                IconBrowserCellRenderer(
                    iconFor = { name -> previews[name] },
                    previewAttempted = { name -> name in completedPreviews },
                )
            accessibleContext.accessibleName = "Taiga UI icon grid"
        }
    private val scrollPane = JBScrollPane(list)

    init {
        title = "Taiga UI Icons"
        setCancelButtonText("Close")
        init()

        search.emptyText.text = "Search icons..."
        search.accessibleContext.accessibleName = "Search Taiga UI icons"
        search.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) {
                    updateFilter()
                }
            },
        )
        list.addListSelectionListener { updateActions() }
        scrollPane.viewport.addChangeListener { requestVisiblePreviews() }
        format.addActionListener { updateActions() }
        copyButton.addActionListener { copySelected() }
        insertButton.addActionListener { insertSelected() }
        updateActions()
        loadCatalog()
    }

    override fun createCenterPanel(): JPanel =
        JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = JBUI.size(720, 520)
            add(search, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    add(
                        JPanel(GridLayout(2, 1)).apply {
                            add(status)
                            add(sourceInfo)
                        },
                        BorderLayout.NORTH,
                    )
                    add(
                        JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                            add(format)
                            add(copyButton)
                            add(insertButton)
                        },
                        BorderLayout.SOUTH,
                    )
                },
                BorderLayout.SOUTH,
            )
        }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun dispose() {
        scope.cancel()
        super.dispose()
    }

    private fun loadCatalog() {
        loadingJob?.cancel()
        loadingJob =
            scope.launch {
                val next =
                    withContext(Dispatchers.IO) {
                        project.service<IconCompletionService>().catalogFor(
                            sourceFile,
                            RefreshCallback(
                                owner = this@IconBrowserDialog,
                                kind = "icon-browser",
                                notifyWhenUnchanged = true,
                                callback = { dialog -> dialog.loadCatalog() },
                            ),
                        )
                    }

                if (next == null) {
                    status.text = "Loading Taiga UI icons..."
                    return@launch
                }

                if (catalog !== next) {
                    catalog = next
                    previewJob?.cancel()
                    previews.clear()
                    completedPreviews.clear()
                }

                updateFilter()
            }
    }

    private fun updateFilter() {
        previewJob?.cancel()
        val source = catalog ?: return
        val matches = filterIconNames(source.names, search.text)

        model.clear()
        matches.forEach(model::addElement)

        status.text =
            when {
                source.names.isEmpty() -> "No icons found. Check installed Taiga UI packages for this project."
                matches.isEmpty() -> "No icons match the search."
                else -> "${matches.size} of ${source.names.size} icons"
            }

        list.clearSelection()
        scrollPane.verticalScrollBar.value = 0
        updateActions()
        requestVisiblePreviews()
    }

    private fun requestVisiblePreviews() {
        previewJob?.cancel()
        val snapshot = catalog ?: return
        val from = list.firstVisibleIndex
        val to = list.lastVisibleIndex

        if (from < 0 || to < from) {
            return
        }

        val names =
            (from..to)
                .map(model::getElementAt)
                .filterNot(completedPreviews::contains)
        val limit = Semaphore(MAX_PARALLEL_PREVIEWS)

        previewJob =
            scope.launch {
                names.forEach { name ->
                    launch(Dispatchers.IO) {
                        limit.withPermit {
                            val icon =
                                snapshot
                                    .svgSource(name)
                                    ?.let { source -> renderer.render(source, GRID_ICON_SIZE) }

                            withContext(Dispatchers.EDT) {
                                if (catalog === snapshot) {
                                    completedPreviews.add(name)
                                    if (icon != null) {
                                        previews[name] = icon
                                    }
                                    list.repaint()
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun updateActions() {
        val selected = list.selectedValue
        val canInsert =
            selected != null &&
                FileEditorManager.getInstance(project).selectedTextEditor?.isViewer == false

        copyButton.isEnabled = selected != null
        insertButton.isEnabled = canInsert
        sourceInfo.text =
            when (selected?.let { name -> catalog?.svgSource(name) }) {
                is IconSvgSource.Local -> "Source: installed Taiga UI package"
                is IconSvgSource.Remote -> "Source: Taiga UI CDN fallback"
                null -> "Select an icon to see its source"
            }
    }

    private fun selectedSnippet(): String? =
        list.selectedValue?.let { name ->
            (format.selectedItem as? IconBrowserSnippet)?.render(name)
        }

    private fun copySelected() {
        val snippet = selectedSnippet() ?: return

        CopyPasteManager.getInstance().setContents(StringSelection(snippet))
    }

    private fun insertSelected() {
        val snippet = selectedSnippet()
        val editor = FileEditorManager.getInstance(project).selectedTextEditor

        if (snippet != null && editor != null && !editor.isViewer) {
            WriteCommandAction.runWriteCommandAction(project) {
                insertIconSnippet(editor, snippet)
            }
        }
    }

    private companion object {
        const val CELL_WIDTH = 148
        const val CELL_HEIGHT = 104
        const val GRID_ICON_SIZE = 56
        const val MAX_PARALLEL_PREVIEWS = 4
    }
}

private class IconBrowserCellRenderer(
    private val iconFor: (String) -> javax.swing.Icon?,
    private val previewAttempted: (String) -> Boolean,
) : JPanel(BorderLayout()),
    ListCellRenderer<String> {
    private val image = JBLabel("", SwingConstants.CENTER)
    private val caption = JBLabel("", SwingConstants.CENTER)

    init {
        border = JBUI.Borders.empty(8)
        add(image, BorderLayout.CENTER)
        add(caption, BorderLayout.SOUTH)
    }

    override fun getListCellRendererComponent(
        list: JList<out String>,
        value: String,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val icon = iconFor(value)

        image.icon = icon
        image.text =
            when {
                icon != null -> ""
                previewAttempted(value) -> "Preview unavailable"
                else -> "…"
            }
        caption.text = value.removePrefix("@tui.")
        caption.toolTipText = value
        background = if (isSelected) list.selectionBackground else list.background
        foreground = if (isSelected) list.selectionForeground else list.foreground
        caption.foreground = foreground
        image.foreground = foreground
        getAccessibleContext().accessibleName = value

        return this
    }
}
