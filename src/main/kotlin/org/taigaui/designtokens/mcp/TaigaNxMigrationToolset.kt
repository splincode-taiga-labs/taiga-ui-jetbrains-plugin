package org.taigaui.designtokens.mcp

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.taigaui.designtokens.migration.ExistingNxMigrationPlan
import org.taigaui.designtokens.migration.TaigaMigrationRunner
import org.taigaui.designtokens.migration.prepareExistingNxMigration
import org.taigaui.designtokens.project.isSelectableProjectDirectory
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path

@Suppress("FunctionName", "unused")
class TaigaNxMigrationToolset : McpToolset {
    @McpTool
    @McpDescription(
        "Read the existing Taiga UI v4 to v5 Nx plan. Returns the workspace, plan path, ordered steps, and plan revision.",
    )
    suspend fun taiga_ui_inspect_nx_migration_plan(
        @McpDescription("Folder relative to the open IDE project root. Use '.' for the root.")
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
        val data =
            buildJsonObject {
                put("status", result.status)
                put("message", result.message)
                put("exitCode", result.exitCode)
                put("output", result.output)
                put("outputTruncated", result.outputTruncated)
            }

        return if (result.status == "failed") {
            McpToolCallResult.error(data.toString(), data)
        } else {
            McpToolCallResult.text(data.toString(), data)
        }
    }
}

private suspend fun Project.loadPlan(directory: String): ExistingNxMigrationPlan =
    withContext(Dispatchers.IO) {
        val root = Path.of(checkNotNull(basePath) { "The open project has no local root." })
        val relative =
            try {
                Path.of(directory)
            } catch (error: InvalidPathException) {
                throw IllegalStateException("Invalid project-relative directory.", error)
            }

        check(!relative.isAbsolute) { "Use a directory relative to the open IDE project root." }
        val selected = root.resolve(relative).normalize()
        check(selected.startsWith(root.normalize()) && relative.none { it.toString() == ".." }) {
            "Select a directory inside the IDE project."
        }

        val folder = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(selected)
        check(readAction { folder?.isSelectableProjectDirectory(this@loadPlan) == true }) {
            "Select a local project directory outside dependencies and symlinks."
        }
        prepareExistingNxMigration(selected, root)
    }
