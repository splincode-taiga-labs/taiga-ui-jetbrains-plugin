package org.taigaui.designtokens.documentation.discovery

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
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
import org.taigaui.designtokens.diagnostics.TaigaDoctorPackage
import org.taigaui.designtokens.diagnostics.TaigaProjectDoctor
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.HierarchyEvent
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

internal class TaigaProjectDoctorPanel(
    private val project: Project,
) : JPanel(BorderLayout()),
    Disposable {
    val packages =
        JBList<TaigaDoctorPackage>().apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = DoctorPackageRenderer()
            getAccessibleContext().accessibleName = "Installed Taiga UI packages"
        }

    private val doctor = TaigaProjectDoctor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private val summary = JBLabel()
    private val reload = JButton("Reload")
    private val openManifest = JButton("Open package.json").apply { isEnabled = false }
    private var source: Path? = null
    private var loadJob: Job? = null

    init {
        add(
            JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                border = JBUI.Borders.empty(6)
                add(summary, BorderLayout.CENTER)
                add(reload, BorderLayout.EAST)
            },
            BorderLayout.NORTH,
        )
        add(JBScrollPane(packages), BorderLayout.CENTER)
        add(
            JPanel(FlowLayout(FlowLayout.TRAILING)).apply { add(openManifest) },
            BorderLayout.SOUTH,
        )

        reload.addActionListener { load(source ?: taigaApiSource(project)) }
        openManifest.addActionListener { openSelectedManifest() }
        packages.addListSelectionListener { openManifest.isEnabled = packages.selectedValue != null }

        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val next = taigaApiSource(project, event.newFile)

                    if (isShowing && next != source && packages.selectedValue?.manifest != next) {
                        load(next)
                    }
                }
            },
        )
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) {
                val next = taigaApiSource(project)

                load(if (packages.selectedValue?.manifest == next) source else next)
            }
        }
    }

    private fun load(path: Path?) {
        source = path
        loadJob?.cancel()
        summary.text = "Checking installed Taiga UI packages…"
        summary.toolTipText = path?.toString()
        packages.setListData(emptyArray())

        loadJob =
            scope.launch {
                val report = withContext(Dispatchers.IO) { path?.let(doctor::inspect) }
                val entries = report?.packages.orEmpty()

                summary.text =
                    report?.reference?.let { reference ->
                        "Taiga UI ${reference.version} (${reference.name})"
                    } ?: "Installed Taiga UI packages"
                packages.emptyText.text = "No Taiga UI packages found for this file"
                packages.setListData(entries.toTypedArray())
            }
    }

    private fun openSelectedManifest() {
        val path = packages.selectedValue?.manifest ?: return

        scope.launch {
            val file = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) }

            if (file != null) {
                OpenFileDescriptor(project, file).navigate(true)
            }
        }
    }

    override fun dispose() = scope.cancel()
}

private class DoctorPackageRenderer : ColoredListCellRenderer<TaigaDoctorPackage>() {
    override fun customizeCellRenderer(
        list: JList<out TaigaDoctorPackage>,
        value: TaigaDoctorPackage,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        append("${value.name}  ${value.version}")

        value.expectedVersion?.let { version ->
            append("  Version mismatch. Expected $version", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        }
    }
}
