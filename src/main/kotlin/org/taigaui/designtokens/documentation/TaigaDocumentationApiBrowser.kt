package org.taigaui.designtokens.documentation

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal data class TaigaDocumentationApiRow(
    val property: TaigaApiProperty,
    val kind: TaigaApiMemberKind,
)

/** Searches an immutable installed API snapshot; typing never resolves PSI or performs IO. */
internal class TaigaDocumentationApiBrowser(
    private val entity: TaigaResolvedDocumentation.Entity,
    initialQuery: String,
    private val openMember: (TaigaResolvedDocumentation.Member) -> Unit,
    private val queryChanged: (String) -> Unit = {},
) : JPanel(BorderLayout(0, JBUI.scale(8))) {
    private val rows =
        entity.entity.inputs.map { TaigaDocumentationApiRow(it, TaigaApiMemberKind.INPUT) } +
            entity.entity.outputs.map { TaigaDocumentationApiRow(it, TaigaApiMemberKind.OUTPUT) }
    private val model = DefaultListModel<TaigaDocumentationApiRow>()
    private val list = JBList(model)
    private val search = JBTextField(initialQuery)
    private val status = JBLabel()

    init {
        isOpaque = false
        search.toolTipText = "Search inputs, outputs, types and descriptions"
        search.getAccessibleContext().accessibleName = "Search Taiga API"
        list.getAccessibleContext().accessibleName = "Taiga inputs and outputs"
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.visibleRowCount = 8
        val renderer = DefaultListCellRenderer()
        list.cellRenderer =
            ListCellRenderer { component, row, index, selected, focused ->
                val prefix = if (row.kind == TaigaApiMemberKind.INPUT) "Input" else "Output"
                val text = "$prefix  ${row.property.name}: ${row.property.documentedType.orEmpty()}"
                renderer.getListCellRendererComponent(component, text, index, selected, focused)
            }
        list.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.clickCount == 2) openSelected()
                }
            },
        )
        list.bind("ENTER", "open-api-member") { openSelected() }
        search.bind("DOWN", "focus-api-results") { list.requestFocusInWindow() }
        bind("control F", "search-api") { search.requestFocusInWindow() }
        bind("meta F", "search-api-mac") { search.requestFocusInWindow() }
        search.document.addDocumentListener(
            object : DocumentListener() {
                override fun insertUpdate(event: DocumentEvent) = filter()

                override fun removeUpdate(event: DocumentEvent) = filter()

                override fun changedUpdate(event: DocumentEvent) = filter()
            },
        )
        add(search, BorderLayout.NORTH)
        add(JBScrollPane(list).apply { preferredSize = JBUI.size(490, 220) }, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        filter()
    }

    val preferredFocus: JComponent get() = search

    private fun filter() {
        val query = search.text.trim()
        val selected = list.selectedValue
        model.removeAllElements()
        rows
            .filter {
                listOfNotNull(it.property.name, it.property.documentedType, it.property.description).any { text ->
                    text.contains(query, ignoreCase = true)
                }
            }.forEach(model::addElement)
        if (!model.isEmpty) {
            list.selectedIndex =
                (0 until model.size).firstOrNull { model.getElementAt(it) == selected } ?: 0
        }
        status.text = "${model.size} of ${rows.size} · ↑/↓ navigate · Enter open · Alt+Left back"
        queryChanged(search.text)
    }

    private fun openSelected() {
        list.selectedValue?.let { row -> openMember(entity.focusedMember(row.property, row.kind)) }
    }
}

internal fun JComponent.bind(
    key: String,
    name: String,
    action: () -> Unit,
) {
    getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key), name)
    actionMap.put(
        name,
        object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) = action()
        },
    )
}
