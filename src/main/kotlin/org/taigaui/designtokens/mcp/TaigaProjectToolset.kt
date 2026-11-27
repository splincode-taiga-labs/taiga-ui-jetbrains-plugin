package org.taigaui.designtokens.mcp

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.components.service
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.taigaui.designtokens.diagnostics.TaigaDoctorMode
import org.taigaui.designtokens.diagnostics.TaigaDoctorPackage
import org.taigaui.designtokens.diagnostics.TaigaProjectDoctor
import org.taigaui.designtokens.diagnostics.collectTaigaDoctorFindings
import org.taigaui.designtokens.documentation.TaigaDocsService
import org.taigaui.designtokens.icons.IconBrowserSnippet
import org.taigaui.designtokens.icons.IconCompletionService
import org.taigaui.designtokens.icons.filterIconNames
import org.taigaui.designtokens.releases.TaigaReleaseFeed
import org.taigaui.designtokens.releases.TaigaReleaseVersion
import org.taigaui.designtokens.settings.TaigaDesignTokensSettings
import org.taigaui.designtokens.units.RemUnitConverter

@Suppress("FunctionName", "unused")
class TaigaProjectToolset : McpToolset {
    @McpTool
    @McpDescription(
        "Read Project Doctor's installed Taiga UI versions, mismatches, and available token, icon, and documentation sources. Does not change files or fetch documentation.",
    )
    suspend fun taiga_ui_inspect_project(
        @McpDescription(TAIGA_SOURCE) source: String,
    ): McpToolCallResult =
        taigaSourceResult(source) { project, file ->
            val docs = project.service<TaigaDocsService>()
            val report = TaigaProjectDoctor().inspectWithSources(file.toNioPath(), docs::cacheStatusFor)
            val sources = report.sources

            buildJsonObject {
                put("networkPolicy", service<TaigaDesignTokensSettings>().networkMode.name)
                put("version", report.reference?.version)
                put("versionSource", report.reference?.name)
                put("packages", report.packages.jsonArray(::packageInfo))
                if (sources != null) {
                    put(
                        "sources",
                        buildJsonObject {
                            put("designTokens", sources.designTokens.jsonStrings())
                            put("icons", sources.icons)
                            put("documentation", sources.documentation?.cacheStatus?.name)
                            put("documentationMajor", sources.documentation?.source?.majorVersion)
                            put("bundledDocumentation", sources.documentation?.bundledAvailable)
                        },
                    )
                }
            }
        }

    @McpTool
    @McpDescription(
        "Run Taiga UI Project Doctor inspections for the selected Angular/Nx app, or inspect v4-to-v5 migration findings. Read-only and bounded to 500 source files.",
    )
    suspend fun taiga_ui_inspect_app(
        @McpDescription(TAIGA_SOURCE) source: String,
        @McpDescription(
            "Use app for general diagnostics or migration for remaining v4-to-v5 findings.",
        ) mode: String = "app",
        @McpDescription(TAIGA_SEARCH_LIMIT) limit: Int = 20,
    ): McpToolCallResult =
        taigaSourceResult(source) { project, file ->
            val doctorMode =
                when (mode) {
                    "app" -> TaigaDoctorMode.APP
                    "migration" -> TaigaDoctorMode.MIGRATION
                    else -> error("Use app or migration for the inspection mode.")
                }
            check(limit in 1..50) { "The result limit must be between 1 and 50." }
            val report =
                checkNotNull(collectTaigaDoctorFindings(project, file.toNioPath(), doctorMode, limit)) {
                    "No application matches this source and inspection mode."
                }

            buildJsonObject {
                put("application", report.application)
                put("mode", mode)
                put("inspectedFiles", report.inspectedFiles)
                put("partial", report.partial)
                put(
                    "findings",
                    report.findings.jsonArray { finding ->
                        buildJsonObject {
                            put("file", finding.file)
                            put("line", finding.line)
                            put("inspection", finding.inspection)
                            put("message", finding.message)
                        }
                    },
                )
            }
        }

    @McpTool
    @McpDescription(
        "Check the newest stable GitHub release in the installed Taiga UI major. Uses cached release information in Cache first and Offline only. Does not post a notification.",
    )
    suspend fun taiga_ui_check_release(
        @McpDescription(TAIGA_SOURCE) source: String,
    ): McpToolCallResult =
        taigaSourceResult(source) { _, file ->
            val version = TaigaProjectDoctor().inspect(file.toNioPath()).reference?.version
            val installed =
                checkNotNull(version?.let(TaigaReleaseVersion::parse)) {
                    "No installed Taiga UI release version was found for this source."
                }
            val latest = service<TaigaReleaseFeed>().latestFor(installed.major)

            buildJsonObject {
                put("installed", version)
                put("latest", latest?.tag)
                put("status", if (latest == null) "unavailable" else "available")
                put("networkPolicy", service<TaigaDesignTokensSettings>().networkMode.name)
                put("releaseUrl", latest?.url)
                put("updateAvailable", latest?.let { it > installed })
            }
        }

    @McpTool
    @McpDescription(
        "Find icon names and Angular snippets from the same effective catalog as the Taiga UI icon browser. May load the configured catalog.",
    )
    suspend fun taiga_ui_search_icons(
        @McpDescription(TAIGA_SOURCE) source: String,
        @McpDescription("Case-insensitive icon name filter. Use an empty string to browse.") query: String,
        @McpDescription(TAIGA_SEARCH_LIMIT) limit: Int = 20,
    ): McpToolCallResult =
        taigaSourceResult(source) { project, file ->
            val catalog = project.service<IconCompletionService>().loadNow(file.toNioPath())
            val icons = filterIconNames(catalog, query)

            buildJsonObject {
                put("total", icons.size)
                put("status", if (catalog.isEmpty()) "unavailable" else "available")
                put("networkPolicy", service<TaigaDesignTokensSettings>().networkMode.name)
                put(
                    "matches",
                    icons.limitedJson(limit) { name ->
                        buildJsonObject {
                            put("name", name)
                            put("element", IconBrowserSnippet.ELEMENT.render(name))
                            put("iconStart", IconBrowserSnippet.ICON_START.render(name))
                        }
                    },
                )
            }
        }

    @McpTool
    @McpDescription(
        "Convert a CSS rem value using the plugin's 16px root font size. Returns the exact decimal pixel value and a CSS snippet.",
    )
    suspend fun taiga_ui_convert_rem(
        @McpDescription("Signed decimal number without a unit, at most 40 characters. Example: '1.25'.") rem: String,
    ): McpToolCallResult {
        val value =
            rem.takeIf { it.length <= 40 && DECIMAL.matches(it) }?.toBigDecimalOrNull()
                ?: return McpToolCallResult.error("Supply a decimal rem value without a unit.")

        return buildJsonObject {
            put("px", JsonPrimitive(RemUnitConverter.toPixels(value)))
            put("css", RemUnitConverter.pxPresentation(value))
        }.toMcpResult()
    }
}

private fun packageInfo(value: TaigaDoctorPackage) =
    buildJsonObject {
        put("name", value.name)
        put("version", value.version)
        put("expectedVersion", value.expectedVersion)
    }

private val DECIMAL = Regex("""[+-]?(?:\d+(?:\.\d*)?|\.\d+)""")
