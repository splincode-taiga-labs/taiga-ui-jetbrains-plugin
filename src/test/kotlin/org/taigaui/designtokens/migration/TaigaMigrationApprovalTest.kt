package org.taigaui.designtokens.migration

import com.intellij.openapi.components.service
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

class TaigaMigrationApprovalTest : HeavyPlatformTestCase() {
    override fun isCreateDirectoryBasedProject(): Boolean = true

    fun testDeclinedMigrationDoesNotReprepareOrRunTheCli() {
        val root = workspace("process.exitCode = 7;")
        val plan = prepare(root)
        val result =
            runInBackground {
                project.service<TaigaMigrationRunner>().confirmAndRunPlan(
                    plan,
                    prepare = { error("Declined migration must not be prepared again") },
                    output = MigrationOutput(),
                    confirmPlan = { false },
                )
            }

        assertEquals("cancelled", result.status)
        assertEquals("The user declined the migration.", result.message)
        assertFalse(Files.exists(root.resolve("migration-ran")))
    }

    fun testApprovedMigrationRunsTheLocalCliAndCapturesOutput() {
        val root =
            workspace(
                """
                const fs = require('node:fs');
                fs.writeFileSync('node_modules/@taiga-ui/cdk/package.json',
                    JSON.stringify({name: '@taiga-ui/cdk', version: '5.34.0'}));
                fs.writeFileSync('migration-ran', JSON.stringify(process.argv.slice(2)));
                console.log('Migration fixture completed');
                """.trimIndent(),
            )
        val plan = prepare(root)
        val result =
            runInBackground {
                project.service<TaigaMigrationRunner>().confirmAndRunPlan(
                    plan,
                    prepare = { prepare(root) },
                    output = MigrationOutput(),
                    confirmPlan = { true },
                )
            }

        assertEquals("success", result.status)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("Migration fixture completed"))
        assertEquals("""["update","@taiga-ui/cdk@5"]""", Files.readString(root.resolve("migration-ran")))
    }

    fun testChangedRevisionAfterApprovalPreventsExecution() {
        val root = workspace("require('node:fs').writeFileSync('migration-ran', 'unexpected');")
        val plan = prepare(root)
        val failure =
            runInBackground {
                runCatching {
                    project.service<TaigaMigrationRunner>().confirmAndRunPlan(
                        plan,
                        prepare = { prepare(root) },
                        output = MigrationOutput(),
                        confirmPlan = {
                            Files.writeString(root.resolve("package.json"), """{"name":"changed"}""")
                            true
                        },
                    )
                }.exceptionOrNull()
            }

        assertTrue(failure is IllegalStateException)
        assertEquals("The workspace or migration plan changed. Inspect the plan again.", failure?.message)
        assertFalse(Files.exists(root.resolve("migration-ran")))
    }

    private fun prepare(root: Path): PreparedMigration {
        val plan = prepareNewTaigaMigration(root, root)

        return PreparedMigration(plan.workspace, newRevision = plan.revision)
    }

    private fun workspace(script: String): Path {
        val projectRoot = getOrCreateProjectBaseDir()
        PsiTestUtil.addContentRoot(module, projectRoot)
        val root = projectRoot.toNioPath().toRealPath()
        val cdk = Files.createDirectories(root.resolve("node_modules/@taiga-ui/cdk"))
        val cli = Files.createDirectories(root.resolve("node_modules/@angular/cli/bin"))
        Files.writeString(root.resolve("angular.json"), "{}")
        Files.writeString(root.resolve("package.json"), "{}")
        Files.writeString(cdk.resolve("package.json"), """{"name":"@taiga-ui/cdk","version":"4.110.0"}""")
        Files.writeString(cli.resolve("ng.js"), script)
        projectRoot.refresh(false, true)

        return root
    }

    private fun <T> runInBackground(action: suspend () -> T): T =
        PlatformTestUtil.waitForFuture(
            AppExecutorUtil.getAppExecutorService().submit(
                Callable { runBlocking { action() } },
            ),
        )
}
