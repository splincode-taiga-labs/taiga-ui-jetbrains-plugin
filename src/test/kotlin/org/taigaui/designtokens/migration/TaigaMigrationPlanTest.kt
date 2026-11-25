package org.taigaui.designtokens.migration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class TaigaMigrationPlanTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun runsAngularUpdateFromTheNearestWorkspaceRoot() =
        runBlocking {
            val project = temporaryFolder.root.toPath()
            workspace(project, TaigaMigrationCli.NX)
            val angular = workspace(project.resolve("packages/standalone"), TaigaMigrationCli.ANGULAR)
            val source = Files.createDirectories(angular.resolve("src/app"))
            val plan = prepareTaigaMigration(source, project)
            val commands = mutableListOf<List<String>>()

            plan.run { arguments ->
                commands.add(arguments)
                installCdk(angular, "5.28.0")
            }

            assertEquals(angular.toRealPath(), plan.root)
            assertEquals(TaigaMigrationCli.ANGULAR, plan.cli)
            assertEquals(listOf(listOf("update", "@taiga-ui/cdk@5")), commands)
        }

    @Test
    fun runsBothNxPhasesFromTheWorkspaceRoot() =
        runBlocking {
            val root = workspace(temporaryFolder.root.toPath(), TaigaMigrationCli.NX)
            val source = Files.createDirectories(root.resolve("apps/demo/src"))
            write(root.resolve("angular.json"), "{}")
            val plan = prepareTaigaMigration(source, root)
            val commands = mutableListOf<List<String>>()

            plan.run { arguments ->
                commands.add(arguments)
                if (commands.size == 1) {
                    write(plan.migrationsFile, """{"migrations":[]}""")
                } else {
                    installCdk(root, "5.28.0")
                }
            }

            assertEquals(root.toRealPath(), plan.root)
            assertEquals(
                listOf(
                    listOf("migrate", "@taiga-ui/cdk@5", "--interactive=false"),
                    listOf("migrate", "--run-migrations=migrations.json"),
                ),
                commands,
            )
        }

    @Test
    fun stopsAfterTheFirstFailedNxCommand() {
        val root = workspace(temporaryFolder.root.toPath(), TaigaMigrationCli.NX)
        val plan = prepareTaigaMigration(root, root)
        val commands = mutableListOf<List<String>>()

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                plan.run { arguments ->
                    commands.add(arguments)
                    error("CLI failed")
                }
            }
        }
        assertEquals(listOf(TaigaMigrationCli.NX.updateArguments), commands)
    }

    @Test
    fun refusesToOverwriteAnExistingNxPlan() {
        val root = workspace(temporaryFolder.root.toPath(), TaigaMigrationCli.NX)
        val plan = prepareTaigaMigration(root, root)
        write(plan.migrationsFile, """{"migrations":[{"name":"pending"}]}""")

        assertThrows(IllegalStateException::class.java) { prepareTaigaMigration(root, root) }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                plan.run { throw AssertionError("A CLI command was started") }
            }
        }
        assertEquals("""{"migrations":[{"name":"pending"}]}""", Files.readString(plan.migrationsFile))
    }

    @Test
    fun rejectsAnIndependentTaigaInstallationAndAnAlreadyMigratedWorkspace() {
        val root = workspace(temporaryFolder.root.toPath(), TaigaMigrationCli.NX)
        val independent = root.resolve("apps/independent")
        installCdk(independent, "4.0.0")

        assertThrows(IllegalStateException::class.java) { prepareTaigaMigration(independent, root) }

        installCdk(root, "5.28.0")

        assertThrows(IllegalStateException::class.java) { prepareTaigaMigration(root, root) }
    }

    private fun workspace(
        root: Path,
        cli: TaigaMigrationCli,
    ): Path {
        val config = if (cli == TaigaMigrationCli.NX) "nx.json" else "angular.json"
        write(root.resolve(config), "{}")
        write(root.resolve("package.json"), "{}")
        write(root.resolve("node_modules").resolve(cli.script), "")
        installCdk(root, "4.66.0")

        return root
    }

    private fun installCdk(
        root: Path,
        version: String,
    ) {
        write(
            root.resolve("node_modules/@taiga-ui/cdk/package.json"),
            """{"name":"@taiga-ui/cdk","version":"$version"}""",
        )
    }

    private fun write(
        file: Path,
        content: String,
    ) {
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
