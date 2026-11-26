package org.taigaui.designtokens.documentation.discovery

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import javax.swing.JButton

class TaigaProjectDoctorPanelTest : BasePlatformTestCase() {
    fun testCopiesTheLoadedAppReportToClipboard() {
        val workspace = Files.createTempDirectory("taiga-doctor-copy")
        val installed = Files.createDirectories(workspace.resolve("node_modules/@taiga-ui"))
        val source = Files.writeString(workspace.resolve("component.ts"), "export class Example {}")

        try {
            listOf("core" to "5.18.0", "kit" to "4.93.0").forEach { (name, version) ->
                val directory = Files.createDirectories(installed.resolve(name))
                Files.writeString(
                    directory.resolve("package.json"),
                    """{"name":"@taiga-ui/$name","version":"$version"}""",
                )
            }
            myFixture.openFileInEditor(
                requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source)),
            )

            val panel = TaigaProjectDoctorPanel(project)
            Disposer.register(testRootDisposable, panel)
            val copy = panel.descendants().filterIsInstance<JButton>().single { it.text == "Copy Report" }

            assertFalse(copy.isEnabled)
            assertEquals("Copy Taiga UI Doctor report", copy.accessibleContext.accessibleName)
            val reload = panel.descendants().filterIsInstance<JButton>().single { it.text == "Reload" }
            reload.doClick()
            await("Doctor report") { copy.isEnabled }

            val clipboard = CopyPasteManager.getInstance()
            val previous = clipboard.contents

            try {
                copy.doClick()
                val report = requireNotNull(clipboard.getContents<String>(DataFlavor.stringFlavor))

                assertTrue(report.contains("Detected release: @taiga-ui/core 5.18.0"))
                assertTrue(report.contains("Version mismatches: 1"))
                assertTrue(report.contains("| @taiga-ui/kit | 4.93.0 | Expected 5.18.0 |"))
                assertFalse(report.contains(workspace.toString()))
            } finally {
                clipboard.setContents(previous ?: StringSelection(""))
            }
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    fun testEmptyWorkspaceKeepsDoctorActionsDisabled() {
        val workspace = Files.createTempDirectory("taiga-doctor-empty")
        val source = Files.writeString(workspace.resolve("component.ts"), "export class Empty {}")

        try {
            val panel = openPanel(source)
            val reload = button(panel, "Reload")
            val analyze = button(panel, "Analyze App")
            val migration = button(panel, "Analyze v4 to v5 Migration")
            val openManifest = button(panel, "Open package.json")
            val copy = button(panel, "Copy Report")

            reload.doClick()
            await("Empty Doctor report") { summary(panel) == "Installed Taiga UI packages" }

            assertEquals(0, panel.packages.model.size)
            assertFalse(analyze.isEnabled)
            assertFalse(migration.isEnabled)
            assertFalse(openManifest.isEnabled)
            assertFalse(copy.isEnabled)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    fun testPackageSelectionControlsManifestActionAndV5Migration() {
        val workspace = Files.createTempDirectory("taiga-doctor-selection")
        val source = Files.writeString(workspace.resolve("component.ts"), "export class Example {}")

        try {
            install(workspace, "core", "5.18.0")
            install(workspace, "kit", "4.93.0")

            val panel = openPanel(source)
            val manifest = button(panel, "Open package.json")
            val migration = button(panel, "Analyze v4 to v5 Migration")
            val analyze = button(panel, "Analyze App")

            button(panel, "Reload").doClick()
            await("Installed packages") { panel.packages.model.size == 2 && analyze.isEnabled }

            assertTrue(migration.isEnabled)
            assertFalse(manifest.isEnabled)
            assertEquals("Taiga UI 5.18.0 (@taiga-ui/core)", summary(panel))
            val labels =
                panel
                    .descendants()
                    .filterIsInstance<JBLabel>()
                    .map { it.text }
                    .toList()
            assertTrue(labels.contains("Design tokens: No installed source found"))
            assertTrue(labels.contains("Icons: No local source found"))
            assertTrue(labels.any { it.startsWith("Documentation: v5") })
            val docs = panel.descendants().filterIsInstance<ActionLink>().single { it.text == "Open documentation" }
            assertTrue(docs.isVisible)

            panel.packages.selectedIndex = 1
            assertTrue(manifest.isEnabled)
            assertEquals("@taiga-ui/kit", panel.packages.selectedValue.name)
            assertEquals("5.18.0", panel.packages.selectedValue.expectedVersion)

            panel.packages.clearSelection()
            assertFalse(manifest.isEnabled)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    fun testLegacyAndIndependentPackagesCannotTriggerV5Migration() {
        for ((name, version) in listOf("core" to "4.93.0", "design-tokens" to "0.322.0")) {
            val workspace = Files.createTempDirectory("taiga-doctor-legacy")
            val source = Files.writeString(workspace.resolve("component.ts"), "export class Example {}")

            try {
                install(workspace, name, version)
                val panel = openPanel(source)
                val copy = button(panel, "Copy Report")

                button(panel, "Reload").doClick()
                await("Installed $name package") { copy.isEnabled }

                assertEquals(1, panel.packages.model.size)
                assertTrue(button(panel, "Analyze App").isEnabled)
                assertFalse(button(panel, "Analyze v4 to v5 Migration").isEnabled)
                assertEquals(
                    if (name == "core") "Taiga UI 4.93.0 (@taiga-ui/core)" else "Installed Taiga UI packages",
                    summary(panel),
                )
            } finally {
                workspace.toFile().deleteRecursively()
            }
        }
    }

    fun testAnalyzeOutsideTheProjectExplainsHowToChooseAnApp() {
        val workspace = Files.createTempDirectory("taiga-doctor-outside")
        val source = Files.writeString(workspace.resolve("component.ts"), "export class Example {}")

        try {
            install(workspace, "core", "5.18.0")
            val panel = openPanel(source)
            val analyze = button(panel, "Analyze App")
            val migration = button(panel, "Analyze v4 to v5 Migration")

            button(panel, "Reload").doClick()
            await("Doctor actions") { analyze.isEnabled && migration.isEnabled }

            analyze.doClick()
            await("App analysis guidance") {
                summary(panel) == "Open a file in the application you want to analyze."
            }
            assertTrue(analyze.isEnabled)

            migration.doClick()
            await("Migration analysis guidance") {
                summary(panel) == "Open a file in an application with Taiga UI 5 installed."
            }
            assertTrue(migration.isEnabled)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    fun testAnalysisDuringIndexingDoesNotStartAndKeepsActionsEnabled() {
        val workspace = Files.createTempDirectory("taiga-doctor-indexing")
        val source = Files.writeString(workspace.resolve("component.ts"), "export class Example {}")

        try {
            install(workspace, "core", "5.18.0")
            val panel = openPanel(source)
            val analyze = button(panel, "Analyze App")
            val migration = button(panel, "Analyze v4 to v5 Migration")

            button(panel, "Reload").doClick()
            await("Doctor actions") { analyze.isEnabled && migration.isEnabled }

            DumbModeTestUtils.runInDumbModeSynchronously(project) {
                analyze.doClick()
                assertTrue(
                    panel.descendants().filterIsInstance<JBLabel>().any {
                        it.text == "Wait for WebStorm indexing before analyzing the application."
                    },
                )
                assertTrue(analyze.isEnabled)
                assertTrue(migration.isEnabled)
            }
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun openPanel(source: java.nio.file.Path): TaigaProjectDoctorPanel {
        myFixture.openFileInEditor(
            requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source)),
        )
        return TaigaProjectDoctorPanel(project).also { Disposer.register(testRootDisposable, it) }
    }

    private fun button(
        panel: TaigaProjectDoctorPanel,
        title: String,
    ): JButton = panel.descendants().filterIsInstance<JButton>().single { it.text == title }

    private fun summary(panel: TaigaProjectDoctorPanel): String? =
        panel
            .descendants()
            .filterIsInstance<JBLabel>()
            .firstOrNull {
                it.text?.startsWith("Taiga UI ") == true ||
                    it.text == "Installed Taiga UI packages" ||
                    it.text?.startsWith("Open a file") == true
            }?.text

    private fun install(
        workspace: java.nio.file.Path,
        name: String,
        version: String,
    ) {
        val directory = Files.createDirectories(workspace.resolve("node_modules/@taiga-ui/$name"))
        Files.writeString(
            directory.resolve("package.json"),
            """{"name":"@taiga-ui/$name","version":"$version"}""",
        )
    }
}
