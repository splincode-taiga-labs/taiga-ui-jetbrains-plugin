package org.taigaui.designtokens.completion

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.ListSelectionModel

internal class FolderColorPreviewDialog(
    private val project: Project,
    private val previews: List<FolderColorPreview>,
) : DialogWrapper(project, true) {
    private val files =
        JBList(previews.map(FolderColorPreview::relativePath)).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            accessibleContext.accessibleName = "Changed stylesheets"
        }
    private val diff = DiffManager.getInstance().createRequestPanel(project, disposable, null)

    init {
        title = FOLDER_COLOR_REPLACEMENT_TITLE
        setOKButtonText("Apply Changes")

        files.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) previews.getOrNull(files.selectedIndex)?.let(::showDiff)
        }
        files.selectedIndex = 0
        init()
    }

    override fun getPreferredFocusedComponent(): JComponent = files

    override fun createCenterPanel(): JComponent =
        JBSplitter(false, 0.25f).apply {
            firstComponent = JBScrollPane(files)
            secondComponent = diff.component
            preferredSize = JBUI.size(900, 520)
        }

    private fun showDiff(preview: FolderColorPreview) {
        val contents = DiffContentFactory.getInstance()

        diff.setRequest(
            SimpleDiffRequest(
                preview.relativePath,
                contents.create(project, preview.original, preview.fileType),
                contents.create(project, preview.proposed, preview.fileType),
                "Current",
                "Proposed",
            ),
        )
    }
}
