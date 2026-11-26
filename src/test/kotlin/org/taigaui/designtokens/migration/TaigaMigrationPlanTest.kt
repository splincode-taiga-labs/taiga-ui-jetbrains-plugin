package org.taigaui.designtokens.migration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun runsAnExistingNxPlanWithoutGeneratingItAgain() =
        runBlocking {
            val root = workspace(temporaryFolder.root.toPath(), TaigaMigrationCli.NX)
            val source = Files.createDirectories(root.resolve("apps/demo/src"))
            val file = root.resolve("migrations.json")
            val savedPlan =
                """
                {
                    "migrations": [
                        {"package":"@taiga-ui/cdk","name":"updateToV5","version":"5.0.0"},
                        {"package":"@nx/angular","name":"update-build","version":"22.0.0"}
                    ]
                }
                """.trimIndent()
            write(file, savedPlan)
            installCdk(root, "5.28.0")
            val plan = prepareExistingNxMigration(source, root)
            val commands = mutableListOf<List<String>>()

            plan.run { arguments -> commands.add(arguments) }

            assertEquals(root.toRealPath(), plan.workspace.root)
            assertEquals(listOf("@taiga-ui/cdk: updateToV5", "@nx/angular: update-build"), plan.migrations)
            assertEquals(listOf(listOf("migrate", "--run-migrations=migrations.json")), commands)
            assertEquals(savedPlan, Files.readString(file))
        }

    @Test
    fun rejectsMissingMalformedAndUnrelatedNxPlans() {
        val root = workspace(temporaryFolder.root.toPath(), TaigaMigrationCli.NX)
        val file = root.resolve("migrations.json")

        assertThrows(IllegalStateException::class.java) { prepareExistingNxMigration(root, root) }

        for (content in listOf(
            "{",
            """{"migrations":[null]}""",
            """{"migrations":[{"package":"@nx/angular","name":"updateToV5","version":"5.0.0"}]}""",
            """{"migrations":[{"package":"@taiga-ui/cdk","name":"updateToV5","version":"4.0.0"}]}""",
        )) {
            write(file, content)

            assertThrows(IllegalStateException::class.java) { prepareExistingNxMigration(root, root) }
        }
    }

    @Test
    fun bindsPlanRevisionToTheWholeFileAndResolvedWorkspace() {
        val root = temporaryFolder.root.toPath()
        val first = workspace(root.resolve("first"), TaigaMigrationCli.NX)
        val second = workspace(root.resolve("second"), TaigaMigrationCli.NX)
        val file = first.resolve("migrations.json")
        val content = """{"migrations":[{"package":"@taiga-ui/cdk","name":"updateToV5","version":"5.0.0"}]}"""
        write(file, content)
        write(second.resolve("migrations.json"), content)
        val plan = prepareExistingNxMigration(first, root)

        prepareExistingNxMigration(first, root).requireRevision(plan.revision)
        assertThrows(IllegalStateException::class.java) {
            prepareExistingNxMigration(second, root).requireRevision(plan.revision)
        }

        write(file, "$content\n")

        assertThrows(IllegalStateException::class.java) {
            prepareExistingNxMigration(first, root).requireRevision(plan.revision)
        }
    }

    @Test
    fun keepsOnlyTheTailOfMigrationOutput() {
        val output = MigrationOutput()
        output.append("early output")
        output.append("x".repeat(15_997))
        output.append("done")

        val result = output.result("failed", "CLI failed", 7)

        assertEquals(16_000, result.output.length)
        assertEquals("done", result.output.takeLast(4))
        assertEquals(true, result.outputTruncated)
        assertEquals(7, result.exitCode)
    }

    @Test
    fun stopsNxMigrationWhenTheCliDoesNotGenerateAPlan() {
        val root = workspace(temporaryFolder.root.toPath(), TaigaMigrationCli.NX)
        val plan = prepareTaigaMigration(root, root)
        val commands = mutableListOf<List<String>>()

        val failure =
            assertThrows(IllegalStateException::class.java) {
                runBlocking { plan.run { commands.add(it) } }
            }

        assertEquals(listOf(TaigaMigrationCli.NX.updateArguments), commands)
        assertTrue(requireNotNull(failure.message).contains("did not generate migrations.json"))
    }

    @Test
    fun rejectsSuccessfulCliExitWithoutInstallingV5() {
        val root = workspace(temporaryFolder.root.toPath(), TaigaMigrationCli.ANGULAR)
        val plan = prepareTaigaMigration(root, root)
        val failure =
            assertThrows(IllegalStateException::class.java) {
                runBlocking { plan.run {} }
            }

        assertTrue(requireNotNull(failure.message).contains("does not have Taiga UI CDK v5 installed"))
    }

    @Test
    fun rejectsFoldersOutsideTheProjectAndMissingWorkspaceDependencies() {
        val root = workspace(temporaryFolder.newFolder("project").toPath(), TaigaMigrationCli.ANGULAR)
        val outside = temporaryFolder.newFolder("outside").toPath()

        assertThrows(IllegalStateException::class.java) { resolveTaigaMigrationWorkspace(outside, root) }

        val file = Files.writeString(root.resolve("source.ts"), "")
        assertThrows(IllegalStateException::class.java) { resolveTaigaMigrationWorkspace(file, root) }

        Files.delete(root.resolve("node_modules").resolve(TaigaMigrationCli.ANGULAR.script))
        assertThrows(IllegalStateException::class.java) { resolveTaigaMigrationWorkspace(root, root) }

        Files.delete(root.resolve("angular.json"))
        assertThrows(IllegalStateException::class.java) { resolveTaigaMigrationWorkspace(root, root) }
    }

    @Test
    fun bindsNewMigrationRevisionToManifestAndInstalledCdk() {
        val root = workspace(temporaryFolder.root.toPath(), TaigaMigrationCli.ANGULAR)
        val first = prepareNewTaigaMigration(root, root)
        val prepared = PreparedMigration(first.workspace, newRevision = first.revision)

        prepared.requireRevision(first.revision)
        write(root.resolve("package.json"), """{"name":"changed"}""")
        val second = prepareNewTaigaMigration(root, root)
        assertThrows(IllegalStateException::class.java) { prepared.requireRevision(second.revision) }

        installCdk(root, "4.70.0")
        val third = prepareNewTaigaMigration(root, root)
        assertNotEquals(second.revision, third.revision)
    }

    @Test
    fun preservesExactOutputLimitAndReplacesTheTailWithLargerChunks() {
        val output = MigrationOutput()
        val exact = "x".repeat(16_000)

        output.append(exact)
        assertEquals(TaigaMigrationResult("success", null, 0, exact), output.result("success", null, 0))

        val replacement = "y".repeat(16_000)
        output.append(replacement)
        assertEquals(TaigaMigrationResult("failed", null, 1, replacement, true), output.result("failed", null, 1))

        val oversized = MigrationOutput()
        oversized.append("discard" + exact)
        assertEquals(TaigaMigrationResult("failed", null, 1, exact, true), oversized.result("failed", null, 1))
    }

    @Test
    fun rejectsInvalidExistingMigrationEntryFields() {
        val root = workspace(temporaryFolder.root.toPath(), TaigaMigrationCli.NX)
        val file = root.resolve("migrations.json")

        for (content in listOf(
            "[]",
            "{}",
            """{"migrations":{}}""",
            """{"migrations":[{}]}""",
            """{"migrations":[{"package":123,"name":"updateToV5"}]}""",
            """{"migrations":[{"package":"@taiga-ui/cdk","name":" ","version":"5.0.0"}]}""",
            """{"migrations":[{"package":"@taiga-ui/cdk","name":"updateToV5","version":5}]}""",
        )) {
            write(file, content)
            assertThrows(IllegalStateException::class.java) { prepareExistingNxMigration(root, root) }
        }
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
