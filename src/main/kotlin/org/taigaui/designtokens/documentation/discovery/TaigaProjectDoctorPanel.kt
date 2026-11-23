package org.taigaui.designtokens.documentation.discovery

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
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
import org.taigaui.designtokens.diagnostics.TaigaDoctorMode
import org.taigaui.designtokens.diagnostics.TaigaDoctorPackage
import org.taigaui.designtokens.diagnostics.TaigaDoctorReport
import org.taigaui.designtokens.diagnostics.TaigaProjectDoctor
import org.taigaui.designtokens.diagnostics.prepareTaigaDoctorAnalysis
import org.taigaui.designtokens.diagnostics.toMarkdown
import org.taigaui.designtokens.migration.TAIGA_V5_MIGRATION_GUIDE
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
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
    private val analyze = JButton("Analyze App").apply { isEnabled = false }
    private val migration = JButton("Analyze v4 to v5 Migration").apply { isEnabled = false }
    private val openManifest = JButton("Open package.json").apply { isEnabled = false }
    private val copyReport = JButton("Copy Report").apply {
        isEnabled = false
        accessibleContext.accessibleName = "Copy Taiga UI Doctor report"
    }
    private var currentReport: TaigaDoctorReport? = null
    private var source: Path? = null
    private var loadJob: Job? = null
    private var analysisJob: Job? = null
    private var supportsV5Migration = false

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
            JPanel(BorderLayout()).apply {
                add(
                    JPanel(FlowLayout(FlowLayout.TRAILING)).apply {
                        add(analyze)
                        add(openManifest)
                        add(copyReport)
                    },
                    BorderLayout.NORTH,
                )
                add(
                    JPanel(FlowLayout(FlowLayout.TRAILING)).apply {
                        add(ActionLink("Migration Guide") { BrowserUtil.browse(TAIGA_V5_MIGRATION_GUIDE) })
                        add(migration)
                    },
                    BorderLayout.SOUTH,
                )
            },
            BorderLayout.SOUTH,
        )

        reload.addActionListener { load(source ?: taigaApiSource(project)) }
        analyze.addActionListener { analyzeApp(TaigaDoctorMode.APP) }
        migration.addActionListener { analyzeApp(TaigaDoctorMode.MIGRATION) }
        openManifest.addActionListener { openSelectedManifest() }
        copyReport.addActionListener {
            currentReport?.let { report ->
                CopyPasteManager.getInstance().setContents(StringSelection(report.toMarkdown()))
            }
        }
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
        analysisJob?.cancel()
        analyze.isEnabled = false
        migration.isEnabled = false
        supportsV5Migration = false
        currentReport = null
        copyReport.isEnabled = false
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
                currentReport = report?.takeIf { entries.isNotEmpty() }
                copyReport.isEnabled = currentReport != null
                analyze.isEnabled = entries.isNotEmpty()
                supportsV5Migration = report?.supportsV5Migration == true
                migration.isEnabled = supportsV5Migration
            }
    }

    private fun analyzeApp(mode: TaigaDoctorMode) {
        val path = source ?: return

        if (DumbService.isDumb(project)) {
            summary.text = "Wait for WebStorm indexing before analyzing the application."
            return
        }

        analyze.isEnabled = false
        migration.isEnabled = false
        analysisJob =
            scope.launch {
                val analysis = prepareTaigaDoctorAnalysis(project, path, mode)

                analyze.isEnabled = true
                migration.isEnabled = supportsV5Migration

                if (analysis == null) {
                    summary.text =
                        when (mode) {
                            TaigaDoctorMode.APP -> "Open a file in the application you want to analyze."
                            TaigaDoctorMode.MIGRATION -> "Open a file in an application with Taiga UI 5 installed."
                        }
                } else {
                    analysis.run(project)
                }
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
