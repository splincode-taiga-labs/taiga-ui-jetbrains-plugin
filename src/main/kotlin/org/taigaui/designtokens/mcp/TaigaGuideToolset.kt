package org.taigaui.designtokens.mcp

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.components.service
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.taigaui.designtokens.documentation.TaigaDocsGuide
import org.taigaui.designtokens.documentation.TaigaDocsService
import org.taigaui.designtokens.migration.TAIGA_V5_MIGRATION_GUIDE

@Suppress("FunctionName", "unused")
class TaigaGuideToolset : McpToolset {
    @McpTool
    @McpDescription(
        "Get Taiga UI generation guidance for the selected app. Returns the installed version and package names, " +
            "the documented checklist and common mistakes. Use taiga_ui_get_examples to verify specific exports.",
    )
    suspend fun taiga_ui_get_generation_context(
        @McpDescription(TAIGA_SOURCE) source: String,
    ): McpToolCallResult =
        taigaSourceResult(source) { project, file ->
            val docs = project.service<TaigaDocsService>()
            val path = file.toNioPath()
            val context = docs.detectContext(path)
            val index = context?.let { docs.indexForMajor(it.majorVersion) }
            val checklist = index?.guides?.get(TaigaDocsGuide.GENERATION_CHECKLIST)
            val commonMistakes = index?.guides?.get(TaigaDocsGuide.COMMON_MISTAKES)

            buildJsonObject {
                put("installedVersion", context?.version)
                put(
                    "installedPackages",
                    context
                        ?.installedPackages
                        .orEmpty()
                        .sorted()
                        .jsonStrings(),
                )
                putDocumentation(index)
                put("checklist", checklist?.take(4_000))
                put("commonMistakes", commonMistakes?.take(4_000))
            }
        }

    @McpTool
    @McpDescription(
        "Read the official Taiga UI v4-to-v5 migration guide for an app on v4 or v5. " +
            "Includes Angular CLI and Nx instructions and troubleshooting. Use taiga_ui_inspect_migration " +
            "to preview commands for a specific v4 workspace. Does not modify project files.",
    )
    suspend fun taiga_ui_get_migration_guide(
        @McpDescription(TAIGA_SOURCE) source: String,
    ): McpToolCallResult =
        taigaSourceResult(source) { project, file ->
            val docs = project.service<TaigaDocsService>()
            val context = docs.detectContext(file.toNioPath())
            val supported = context?.majorVersion in setOf(4, 5)
            val index = if (supported) docs.indexForMajor(5) else null
            val guide = index?.guides?.get(TaigaDocsGuide.MIGRATION)

            buildJsonObject {
                put("installedVersion", context?.version)
                put("fromMajor", 4)
                put("toMajor", 5)
                put(
                    "status",
                    when {
                        guide != null -> "available"
                        supported -> "documentation_unavailable"
                        context == null -> "version_unavailable"
                        else -> "unsupported_version"
                    },
                )
                putDocumentation(index)
                put("documentationUrl", TAIGA_V5_MIGRATION_GUIDE)
                put(
                    "markdown",
                    guide?.replace("{CURRENT_MAJOR}", "4")?.replace("{NEXT_MAJOR}", "5")?.take(12_000),
                )
            }
        }
}
