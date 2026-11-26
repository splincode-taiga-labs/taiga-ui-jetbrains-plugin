package org.taigaui.designtokens.migration

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue

class TaigaMigrationCommandTest : BasePlatformTestCase() {
    fun testCommandUsesWorkspaceArgumentsAndEnvironment() {
        withScript(
            """
            console.log(JSON.stringify(process.argv.slice(2)));
            console.log(process.cwd());
            console.log('NO_COLOR=' + process.env.NO_COLOR);
            console.error('fixture stderr');
            """.trimIndent(),
        ) { plan ->
            val output = ConcurrentLinkedQueue<Pair<String, Key<*>>>()

            runBlocking {
                withContext(Dispatchers.IO) {
                    runMigrationCommand(node(), plan, listOf("update", "@taiga-ui/cdk@5")) { text, key ->
                        output.add(text to key)
                    }
                }
            }

            val stdout = output.filter { it.second == ProcessOutputTypes.STDOUT }.joinToString("") { it.first }
            val stderr = output.filter { it.second == ProcessOutputTypes.STDERR }.joinToString("") { it.first }
            assertTrue(stdout, stdout.contains("""["update","@taiga-ui/cdk@5"]"""))
            assertTrue(stdout, stdout.contains(plan.root.toString()))
            assertTrue(stdout, stdout.contains("NO_COLOR=1"))
            assertTrue(stderr, stderr.contains("fixture stderr"))
            val command = "> ng update @taiga-ui/cdk@5\n"
            assertTrue(output.any { it.first == command && it.second == ProcessOutputTypes.SYSTEM })
        }
    }

    fun testNonZeroExitCodeIsReportedAsFailure() {
        withScript("process.exitCode = 7;") { plan ->
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    runBlocking {
                        withContext(Dispatchers.IO) {
                            runMigrationCommand(node(), plan, listOf("update")) { _, _ -> }
                        }
                    }
                }

            assertEquals("ng update failed (exit code 7).", failure.message)
        }
    }

    private fun node() =
        requireNotNull(PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("node")) {
            "Node.js is required for the migration command tests."
        }

    private fun withScript(
        source: String,
        action: (TaigaMigrationPlan) -> Unit,
    ) {
        val root = Files.createTempDirectory("taiga migration command").toRealPath()
        val plan = TaigaMigrationPlan(root, TaigaMigrationCli.ANGULAR)

        try {
            writeScript(plan.script, source)
            action(plan)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun writeScript(
        script: Path,
        source: String,
    ) {
        Files.createDirectories(script.parent)
        Files.writeString(script, source)
    }
}
