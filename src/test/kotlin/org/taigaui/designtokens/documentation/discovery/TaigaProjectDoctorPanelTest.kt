package org.taigaui.designtokens.documentation.discovery

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
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
}
