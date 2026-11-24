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

    @Test
    fun ignoresSourcesOutsideWorkspaceAndDependencyDirectories() =
        runBlocking {
            val workspace = temporaryFolder.newFolder().toPath()
            val outside = workspace.parent.resolve("outside-component.ts")

            assertNull(findTaigaDoctorApplication(outside, workspace) { error("Unexpected Angular config") })

            for (dependency in listOf("node_modules", ".yarn")) {
                val dependencyRoot = workspace.resolve("apps/demo").resolve(dependency)
                val directory = Files.createDirectories(dependencyRoot.resolve("package"))
                val source = Files.createFile(directory.resolve("component.ts"))

                assertNull(findTaigaDoctorApplication(source, workspace) { error("Unexpected Angular config") })
            }
        }

    @Test
    fun prefersTheClosestNxProjectAndDoesNotScanTheMonorepoRoot() =
        runBlocking {
            val workspace = temporaryFolder.newFolder().toPath()
            Files.createFile(workspace.resolve("nx.json"))
            Files.createFile(workspace.resolve("package.json"))
            val app = Files.createDirectories(workspace.resolve("apps/demo"))
            Files.createFile(app.resolve("project.json"))
            val nested = Files.createDirectories(app.resolve("features/feature"))
            Files.createFile(nested.resolve("project.json"))
            val source = Files.createFile(nested.resolve("component.ts"))

            assertEquals(nested, findTaigaDoctorApplication(source, workspace) { emptyList() })
            assertNull(findTaigaDoctorApplication(workspace.resolve("root.ts"), workspace) { emptyList() })
        }

    @Test
    fun ignoresInvalidAndEscapingAngularRootsAndUsesTheMostSpecificValidRoot() =
        runBlocking {
            val workspace = temporaryFolder.newFolder().toPath()
            val config = Files.createFile(workspace.resolve("angular.json"))
            val app = Files.createDirectories(workspace.resolve("projects/modern/src"))
            val source = Files.createFile(app.resolve("component.ts"))

            assertEquals(
                workspace.resolve("projects/modern"),
                findTaigaDoctorApplication(source, workspace) { candidate ->
                    assertEquals(config, candidate)
                    listOf("../outside", "\u0000invalid", "projects", "projects/modern")
                },
            )
        }

    @Test
    fun fallsBackToTheNearestPackageInARegularWorkspace() =
        runBlocking {
            val workspace = temporaryFolder.newFolder().toPath()
            Files.createFile(workspace.resolve("package.json"))
            val app = Files.createDirectories(workspace.resolve("packages/demo"))
            Files.createFile(app.resolve("package.json"))
            val source = Files.createFile(Files.createDirectories(app.resolve("src")).resolve("component.ts"))

            assertEquals(app, findTaigaDoctorApplication(source, workspace) { emptyList() })
            assertEquals(workspace, findTaigaDoctorApplication(workspace.resolve("root.ts"), workspace) { emptyList() })
        }

    @Test
    fun usesTheWorkspaceWhenThereIsNoProjectConfiguration() =
        runBlocking {
            val workspace = temporaryFolder.newFolder().toPath()
            val directory = Files.createDirectories(workspace.resolve("src"))

            assertEquals(workspace, findTaigaDoctorApplication(directory, workspace) { emptyList() })
        }

    @Test
    fun doesNotFallBackToTheMonorepoRootForAnUnconfiguredAngularApp() =
        runBlocking {
            val workspace = temporaryFolder.newFolder().toPath()
            Files.createFile(workspace.resolve("angular.json"))
            Files.createFile(workspace.resolve("package.json"))
            val app = Files.createDirectories(workspace.resolve("apps/unconfigured"))
            val source = Files.createFile(app.resolve("component.ts"))

            assertNull(
                findTaigaDoctorApplication(source, workspace) { listOf("apps/configured") },
            )
        }

    @Test
    fun choosesTheMostSpecificRootAcrossNxAndAngularConfiguration() =
        runBlocking {
            val workspace = temporaryFolder.newFolder().toPath()
            Files.createFile(workspace.resolve("angular.json"))
            val app = Files.createDirectories(workspace.resolve("apps/demo"))
            Files.createFile(app.resolve("project.json"))
            val nested = Files.createDirectories(app.resolve("feature"))
            val source = Files.createFile(nested.resolve("component.ts"))

            assertEquals(
                nested,
                findTaigaDoctorApplication(source, workspace) { listOf("apps/demo/feature") },
            )
            assertEquals(
                app,
                findTaigaDoctorApplication(source, workspace) { listOf("apps") },
            )
        }

    @Test
    fun allowsPackageFallbackInsideAnOtherwiseUnconfiguredMonorepo() =
        runBlocking {
            val workspace = temporaryFolder.newFolder().toPath()
            Files.createFile(workspace.resolve("nx.json"))
            Files.createFile(workspace.resolve("package.json"))
            val app = Files.createDirectories(workspace.resolve("packages/demo"))
            Files.createFile(app.resolve("package.json"))
            val source = Files.createFile(app.resolve("component.ts"))

            assertEquals(app, findTaigaDoctorApplication(source, workspace) { emptyList() })
        }

    @Test
    fun readsTheNearestAngularConfigurationInsteadOfTheWorkspaceConfiguration() =
        runBlocking {
            val workspace = temporaryFolder.newFolder().toPath()
            Files.createFile(workspace.resolve("angular.json"))
            val app = Files.createDirectories(workspace.resolve("apps/demo"))
            val config = Files.createFile(app.resolve("angular.json"))
            val source = Files.createFile(Files.createDirectories(app.resolve("src")).resolve("component.ts"))

            assertEquals(
                app.resolve("src"),
                findTaigaDoctorApplication(source, workspace) { candidate ->
                    assertEquals(config, candidate)
                    listOf("src")
                },
            )
        }
}
