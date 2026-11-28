package org.taigaui.designtokens.mcp

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.mcpserver.impl.util.asTools
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import org.taigaui.designtokens.settings.TaigaDesignTokensSettings

class TaigaMcpToolsProvider : McpToolsProvider {
    // Isolate reflection failures to the affected toolset.
    @Suppress("TooGenericExceptionCaught")
    private val reflectedTools by lazy {
        listOf(
            TaigaMigrationToolset(),
            TaigaProjectToolset(),
            TaigaApiToolset(),
            TaigaExampleToolset(),
            TaigaGuideToolset(),
            TaigaTokenToolset(),
            TaigaFolderColorToolset(),
        ).flatMap { toolset ->
            try {
                toolset.asTools()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger<TaigaMcpToolsProvider>().warn(
                    "Cannot load MCP tools for ${toolset.javaClass.simpleName}",
                    failure,
                )
                emptyList()
            }
        }.map { tool -> TaigaRestrictedMcpTool(tool, tool.descriptor.name in PROJECT_CHANGE_TOOLS) }
    }

    override fun getTools(): List<McpTool> {
        val settings = service<TaigaDesignTokensSettings>()
        if (!settings.exposeMcpTools) return emptyList()

        return if (settings.allowMcpChanges) {
            reflectedTools
        } else {
            reflectedTools.filterNot(
                TaigaRestrictedMcpTool::changesProject,
            )
        }
    }
}

private class TaigaRestrictedMcpTool(
    private val tool: McpTool,
    val changesProject: Boolean,
) : McpTool by tool {
    override suspend fun call(args: JsonObject): McpToolCallResult =
        taigaMcpDenial(changesProject)?.let { McpToolCallResult.error(it) } ?: tool.call(args)
}

private val PROJECT_CHANGE_TOOLS =
    setOf(
        "taiga_ui_apply_folder_colors",
        "taiga_ui_run_migration",
        "taiga_ui_run_nx_migration_plan",
    )
