package org.taigaui.designtokens.mcp

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.taigaui.designtokens.migration.ExistingNxMigrationPlan
import org.taigaui.designtokens.migration.RUN_NX_MIGRATIONS
import org.taigaui.designtokens.migration.TaigaMigrationCli
import org.taigaui.designtokens.migration.TaigaMigrationResult
import org.taigaui.designtokens.migration.TaigaMigrationRunner
import org.taigaui.designtokens.migration.prepareExistingNxMigration
import org.taigaui.designtokens.migration.prepareNewTaigaMigration
import java.io.IOException
import java.nio.file.Path

@Suppress("FunctionName", "unused")
class TaigaMigrationToolset : McpToolset {
    @McpTool
    @McpDescription("Preview a new official Taiga UI v4 to v5 migration in the selected Angular or Nx workspace.")
    suspend fun taiga_ui_inspect_migration(
        @McpDescription(MIGRATION_DIRECTORY) directory: String,
    ): McpToolCallResult =
        taigaSourceResult(directory) { project, folder ->
            check(folder.isDirectory) { "Select a project directory for the migration." }
            val plan = prepareNewTaigaMigration(folder.toNioPath(), project.migrationRoot())
            val cli = plan.workspace.cli

            buildJsonObject {
                put("workspace", plan.workspace.root.toString())
                put("cli", cli.command)
                put("updateArguments", cli.updateArguments.jsonStrings())
                if (cli == TaigaMigrationCli.NX) {
                    put("runGeneratedPlanArguments", RUN_NX_MIGRATIONS.jsonStrings())
                }
                put("revision", plan.revision)
            }
        }

    @McpTool
    @McpDescription(
        "Start the official Taiga UI v4 to v5 migration after the user approves in WebStorm. " +
            "May install dependencies and edit workspace files. Inspect first. Returns status and bounded CLI output.",
    )
    suspend fun taiga_ui_run_migration(
        @McpDescription(MIGRATION_DIRECTORY) directory: String,
        @McpDescription("Revision returned by taiga_ui_inspect_migration for the same directory.") revision: String,
    ): McpToolCallResult {
        val project = currentCoroutineContext().project
        val result =
            project.service<TaigaMigrationRunner>().runNewMigration(revision) {
                withContext(Dispatchers.IO) {
                    prepareNewTaigaMigration(project.migrationFolder(directory), project.migrationRoot())
                }
            }

        return result.toMcpResult()
    }

    @McpTool
    @McpDescription(
        "Read the existing Taiga UI v4 to v5 Nx plan. Returns the workspace, plan path, ordered steps, and plan revision.",
    )
    suspend fun taiga_ui_inspect_nx_migration_plan(
        @McpDescription(MIGRATION_DIRECTORY)
        directory: String,
    ): McpToolCallResult {
        val project = currentCoroutineContext().project
        return try {
            val plan = project.loadPlan(directory)
            val data =
                buildJsonObject {
                    put("workspace", plan.workspace.root.toString())
                    put("planPath", plan.workspace.migrationsFile.toString())
                    put("revision", plan.revision)
                    put(
                        "steps",
                        buildJsonArray {
                            for (entry in plan.entries) {
                                add(
                                    buildJsonObject {
                                        put("package", entry.packageName)
                                        put("name", entry.name)
                                        put("version", entry.version)
                                    },
                                )
                            }
                        },
                    )
                }

            McpToolCallResult.text(data.toString(), data)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalStateException) {
            McpToolCallResult.error(error.message ?: "Cannot inspect the Nx migration plan.")
        } catch (error: IOException) {
            McpToolCallResult.error(error.message ?: "Cannot read the Nx migration plan.")
        }
    }

    @McpTool
    @McpDescription(
        "Run the complete saved Nx plan after the user approves it in WebStorm. " +
            "May install dependencies, edit workspace files, and rerun applied steps. " +
            "Inspect first. Returns status, exit code when available, and the last 16000 characters of CLI output.",
    )
    suspend fun taiga_ui_run_nx_migration_plan(
        @McpDescription("Use the same project-relative folder as in the inspection call.")
        directory: String,
        @McpDescription(
            "Revision returned by taiga_ui_inspect_nx_migration_plan. WebStorm also requests user approval.",
        )
        revision: String,
    ): McpToolCallResult {
        val project = currentCoroutineContext().project
        val result = project.service<TaigaMigrationRunner>().runSavedNxPlan(revision) { project.loadPlan(directory) }

        return result.toMcpResult()
    }
}

private suspend fun Project.loadPlan(directory: String): ExistingNxMigrationPlan =
    withContext(Dispatchers.IO) { prepareExistingNxMigration(migrationFolder(directory), migrationRoot()) }

private fun Project.migrationRoot(): Path = Path.of(checkNotNull(basePath) { "The open project has no local root." })

private suspend fun Project.migrationFolder(directory: String): Path {
    val folder = taigaMcpSource(directory)
    check(folder.isDirectory) { "Select a project directory for the migration." }

    return folder.toNioPath()
}

private fun TaigaMigrationResult.toMcpResult(): McpToolCallResult {
    val data =
        buildJsonObject {
            put("status", status)
            put("message", message)
            put("exitCode", exitCode)
            put("output", output)
            put("outputTruncated", outputTruncated)
        }

    return if (status == "failed") {
        McpToolCallResult.error(data.toString(), data)
    } else {
        data.toMcpResult()
    }
}

private const val MIGRATION_DIRECTORY = "Folder relative to the open IDE project root. Use '.' for the root."
