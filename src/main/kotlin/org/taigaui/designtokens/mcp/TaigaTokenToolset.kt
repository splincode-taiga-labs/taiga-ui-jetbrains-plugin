package org.taigaui.designtokens.mcp

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.components.service
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.taigaui.designtokens.completion.DesignTokenColorLookup
import org.taigaui.designtokens.completion.isLocalStylesheet
import org.taigaui.designtokens.documentation.discovery.TaigaTokenExplorerCatalog
import org.taigaui.designtokens.project.DesignTokenCatalogEntry
import org.taigaui.designtokens.project.DesignTokenIndexService
import org.taigaui.designtokens.resolution.CssLiteralColor

@Suppress("FunctionName", "unused")
class TaigaTokenToolset : McpToolset {
    @McpTool
    @McpDescription(
        "Search project and installed Taiga UI design token names. Distinguishes effective tokens, project overrides, and catalog-only names.",
    )
    suspend fun taiga_ui_search_tokens(
        @McpDescription(TAIGA_SOURCE) source: String,
        @McpDescription("Case-insensitive token name filter. Use an empty string to browse.") query: String,
        @McpDescription(TAIGA_SEARCH_LIMIT) limit: Int = 20,
    ): McpToolCallResult =
        taigaSourceResult(source) { project, file ->
            val catalog = TaigaTokenExplorerCatalog.load(project.service<DesignTokenIndexService>(), file.toNioPath())
            val matches = catalog.search(query)

            buildJsonObject {
                put("total", matches.size)
                put("matches", matches.limitedJson(limit, ::tokenInfo))
            }
        }

    @McpTool
    @McpDescription(
        "Resolve a named Taiga UI token for the selected workspace. Returns the Token Explorer's values, contexts, source declarations and reference chains, including unresolved states.",
    )
    suspend fun taiga_ui_resolve_token(
        @McpDescription(TAIGA_SOURCE) source: String,
        @McpDescription("Exact CSS custom property name, including --tui-.") name: String,
    ): McpToolCallResult =
        taigaSourceResult(source) { project, file ->
            val catalog = TaigaTokenExplorerCatalog.load(project.service<DesignTokenIndexService>(), file.toNioPath())
            val entry =
                checkNotNull(catalog.search(name).firstOrNull { it.name == name }) {
                    "This token is unavailable in the selected workspace."
                }
            val model = catalog.details(entry)

            buildJsonObject {
                put("token", tokenInfo(entry))
                put("description", model.description)
                put(
                    "sections",
                    model.sections.jsonArray { section ->
                        buildJsonObject {
                            put("package", section.packageName)
                            put(
                                "values",
                                section.rows.jsonArray { row ->
                                    buildJsonObject {
                                        put("context", row.platform)
                                        put("value", row.resolvedValue)
                                        put("file", row.navigationTarget?.sourceFile?.toString())
                                        put("line", row.navigationTarget?.line)
                                        put("override", row.overrideMessage)
                                    }
                                },
                            )
                            put(
                                "referenceChains",
                                section.chains.jsonArray { chain ->
                                    buildJsonObject {
                                        put("context", chain.platform)
                                        put("override", chain.overrideMessage)
                                        put(
                                            "lines",
                                            chain.lines.jsonArray { line ->
                                                buildJsonObject {
                                                    put("text", line.text)
                                                    put("depth", line.depth)
                                                }
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    },
                )
            }
        }

    @McpTool
    @McpDescription(
        "Find exact Taiga UI token matches for a CSS color in an existing stylesheet using the plugin's color quick fix rules. Returns all matches, including ambiguous matches.",
    )
    suspend fun taiga_ui_match_color(
        @McpDescription("Project-relative path to an existing CSS, Less or SCSS file.") source: String,
        @McpDescription("Single hexadecimal, rgb() or rgba() CSS color value.") color: String,
    ): McpToolCallResult =
        taigaSourceResult(source) { project, file ->
            check(!file.isDirectory && file.isLocalStylesheet()) { "Select an existing CSS, Less or SCSS file." }
            val literal = checkNotNull(CssLiteralColor.parse(color.trim())) { "Supply a supported CSS color literal." }
            val names = project.service<DesignTokenColorLookup>().buildMatches(file)[literal].orEmpty()

            buildJsonObject {
                put("matches", names.jsonStrings())
                put("ambiguous", names.size > 1)
            }
        }
}

private fun tokenInfo(token: DesignTokenCatalogEntry) =
    buildJsonObject {
        put("name", token.name)
        put("source", token.source.name)
        put("package", token.packageName)
        put("effective", token.effective)
        put("deprecated", token.deprecation != null)
        put("replacement", token.deprecation?.replacement)
        put("deprecationMessage", token.deprecation?.message)
    }
