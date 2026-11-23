package org.taigaui.designtokens.diagnostics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class TaigaDoctorAnalysisScopeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun scopesNxAnalysisToTheActiveAppWithHoistedDependencies() =
        runBlocking {
            val workspace = temporaryFolder.newFolder().toPath()
            Files.createFile(workspace.resolve("nx.json"))
            Files.createFile(workspace.resolve("package.json"))

            for (name in listOf("legacy", "modern")) {
                val app = Files.createDirectories(workspace.resolve("apps/$name"))
                Files.createFile(app.resolve("project.json"))
                val source = Files.createFile(app.resolve("component.ts"))

                assertEquals(app, findTaigaDoctorApplication(source, workspace) { emptyList() })
            }

            assertNull(findTaigaDoctorApplication(workspace.resolve("nx.json"), workspace) { emptyList() })
        }

    @Test
    fun scopesAngularAnalysisToTheMostSpecificConfiguredRoot() =
        runBlocking {
            val workspace = temporaryFolder.newFolder().toPath()
            val angularConfig = Files.createFile(workspace.resolve("angular.json"))
            Files.createFile(workspace.resolve("package.json"))
            val app = Files.createDirectories(workspace.resolve("projects/modern"))
            val source = Files.createFile(app.resolve("component.ts"))

            assertEquals(
                app,
                findTaigaDoctorApplication(source, workspace) { config ->
                    assertEquals(angularConfig, config)
                    listOf("", "projects/modern")
                },
            )
        }
}
