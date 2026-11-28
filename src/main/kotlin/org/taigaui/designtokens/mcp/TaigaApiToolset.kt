package org.taigaui.designtokens.mcp

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.taigaui.designtokens.documentation.TaigaApiProperty
import org.taigaui.designtokens.documentation.TaigaDocsIndex
import org.taigaui.designtokens.documentation.TaigaDocsService
import org.taigaui.designtokens.documentation.TaigaDocumentationRequest
import org.taigaui.designtokens.documentation.TaigaDocumentationResolver
import org.taigaui.designtokens.documentation.TaigaResolvedDocumentation
import org.taigaui.designtokens.documentation.canonicalImport
import org.taigaui.designtokens.documentation.description
import org.taigaui.designtokens.documentation.discovery.TaigaApiCatalog
import org.taigaui.designtokens.documentation.documentationUri
import org.taigaui.designtokens.documentation.packageName
import org.taigaui.designtokens.documentation.presentationName
import org.taigaui.designtokens.documentation.resolveDocumentation
import org.taigaui.designtokens.documentation.typeText

@Suppress("FunctionName", "unused")
class TaigaApiToolset : McpToolset {
    @McpTool
    @McpDescription(
        "Search the version-aware Taiga UI API catalog using the same ranking as Search Everywhere. Results describe published APIs and examples for the installed major. Use taiga_ui_api_at to verify installed bindings.",
    )
    suspend fun taiga_ui_search_api(
        @McpDescription(TAIGA_SOURCE) source: String,
        @McpDescription("Display name, public symbol, or Angular selector. Use an empty string to browse.") query:
            String,
        @McpDescription(TAIGA_SEARCH_LIMIT) limit: Int = 20,
    ): McpToolCallResult =
        taigaSourceResult(source) { project, file ->
            val sourcePath = file.toNioPath()
            val catalog = TaigaApiCatalog.load(project, sourcePath)
            val installedVersion =
                catalog?.version ?: project.service<TaigaDocsService>().detectContext(sourcePath)?.version
            val matches = catalog?.search(query).orEmpty()

            buildJsonObject {
                put("installedVersion", installedVersion)
                putDocumentation(catalog?.index)
                if (catalog == null) {
                    put(
                        "message",
                        "Documentation is unavailable. Use taiga_ui_api_at at a source location for installed API.",
                    )
                }
                put("total", matches.size)
                put(
                    "matches",
                    matches.limitedJson(limit) { match ->
                        val entry = match.entry
                        buildJsonObject {
                            put("sectionId", entry.entity.sectionId)
                            put("title", entry.entity.title)
                            put("kind", entry.entity.kind.name)
                            put("packages", entry.packages.jsonStrings())
                            put("symbols", entry.entity.publicSymbols.jsonStrings())
                            put("selectors", entry.entity.selectors.jsonStrings())
                            put("summary", entry.summary)
                            put("hasExample", entry.entity.example != null)
                            put("documentationUrl", entry.entity.documentationUri.toString())
                        }
                    },
                )
            }
        }

    @McpTool
    @McpDescription(
        "Read a published Taiga UI documentation entry. Returns the documented inputs, outputs and example for the selected workspace version. Use taiga_ui_api_at to verify an installed binding in source.",
    )
    suspend fun taiga_ui_read_api(
        @McpDescription(TAIGA_SOURCE) source: String,
        @McpDescription("Exact sectionId returned by taiga_ui_search_api.") sectionId: String,
    ): McpToolCallResult =
        taigaSourceResult(source) { project, file ->
            val docs = project.service<TaigaDocsService>()
            val sourcePath = file.toNioPath()
            val snapshot = docs.snapshotFor(sourcePath)
            val entry = snapshot?.findBySectionId(sectionId)
            val installedVersion = snapshot?.projectContext?.version ?: docs.detectContext(sourcePath)?.version

            buildJsonObject {
                put("installedVersion", installedVersion)
                putDocumentation(snapshot?.index)
                put("status", if (entry == null) "unavailable" else "available")
                if (entry == null) {
                    put("sectionId", sectionId)
                    put(
                        "message",
                        "This entry is unavailable. Use taiga_ui_api_at at a source location for installed API.",
                    )
                    return@buildJsonObject
                }
                put("sectionId", entry.sectionId)
                put("title", entry.title)
                put("kind", entry.kind.name)
                put("description", entry.description?.take(4_000))
                put("documentationUrl", entry.documentationUri.toString())
                put("documentedInputs", entry.inputs.jsonArray(::propertyInfo))
                put("documentedOutputs", entry.outputs.jsonArray(::propertyInfo))
                put("example", entry.example?.code?.take(6_000))
                put("exampleLanguage", entry.example?.language)
            }
        }

    @McpTool
    @McpDescription(
        "Inspect a Taiga UI component, pipe, input or output at a source location using the installed declarations and Quick Documentation resolver. May fetch versioned descriptions.",
    )
    suspend fun taiga_ui_api_at(
        @McpDescription("Project-relative path to the Angular template or TypeScript source file.") source: String,
        @McpDescription("1-based line number.") line: Int,
        @McpDescription("1-based UTF-16 column within the line.") column: Int,
    ): McpToolCallResult =
        taigaSourceResult(source) { project, file ->
            val request =
                checkNotNull(documentationRequest(project, file, line, column)) {
                    "No Taiga UI API was found at this location."
                }
            val docs = project.service<TaigaDocsService>()
            val sourcePath = file.toNioPath()
            val snapshot = docs.snapshotFor(sourcePath)
            val installedVersion = snapshot?.projectContext?.version ?: docs.detectContext(sourcePath)?.version
            val resolved =
                checkNotNull(resolveDocumentation(request, snapshot)) {
                    "No Taiga UI documentation is available at this location."
                }

            buildJsonObject {
                resolvedApi(resolved).forEach { (key, value) -> put(key, value) }
                put("installedVersion", installedVersion)
                val origin =
                    if (resolved.subject.localDocumentation.source ==
                        null
                    ) {
                        "DOCUMENTATION"
                    } else {
                        "INSTALLED_DECLARATIONS"
                    }
                put("apiOrigin", origin)
                putDocumentation(snapshot?.index)
            }
        }
}

private fun documentationRequest(
    project: Project,
    file: VirtualFile,
    line: Int,
    column: Int,
): TaigaDocumentationRequest? =
    ReadAction
        .nonBlocking<TaigaDocumentationRequest?> {
            val psi = PsiManager.getInstance(project).findFile(file)
            val document = checkNotNull(psi?.viewProvider?.document) { "Select a source file with an IDE document." }
            check(line in 1..document.lineCount && column >= 1) { "Line or column is outside this source file." }
            val start = document.getLineStartOffset(line - 1)
            val end = document.getLineEndOffset(line - 1)
            check(column - 1 <= end - start) { "Column is outside this source line." }

            TaigaDocumentationResolver.findRequest(checkNotNull(psi), start + column - 1)
        }.withDocumentsCommitted(project)
        .inSmartMode(project)
        .expireWith(project)
        .executeSynchronously()

internal fun resolvedApi(value: TaigaResolvedDocumentation) =
    buildJsonObject {
        put("name", value.presentationName)
        put("package", value.packageName)
        put("description", value.description?.take(4_000))
        put("documentationUrl", value.documentationUri.toString())
        put("import", value.canonicalImport())
        when (value) {
            is TaigaResolvedDocumentation.Entity -> {
                put("kind", value.entity.kind.name)
                put("installedBindings", value.subject.localDocumentation.angularResolved)
                put(
                    "requiredInputs",
                    value.subject.localDocumentation.requiredInputs
                        .sorted()
                        .jsonStrings(),
                )
                put("inputs", value.entity.inputs.jsonArray(::propertyInfo))
                put("outputs", value.entity.outputs.jsonArray(::propertyInfo))
                put("type", value.typeText?.take(2_000))
            }

            is TaigaResolvedDocumentation.Member -> {
                put("kind", value.kind.name)
                put(
                    "receivers",
                    value.receivers.ifEmpty { listOf(value) }.jsonArray { receiver ->
                        buildJsonObject {
                            put("owner", receiver.subject.presentationName)
                            put("package", receiver.packageName)
                            put("type", receiver.typeText?.take(2_000))
                            put("installedType", receiver.localMember?.type)
                            put("required", receiver.localMember?.required)
                        }
                    },
                )
            }
        }
    }

internal fun JsonObjectBuilder.putDocumentation(index: TaigaDocsIndex?) {
    val provenance = index?.provenance

    put(
        "documentation",
        buildJsonObject {
            put("available", index != null)
            put("major", index?.source?.majorVersion)
            put("origin", provenance?.origin?.name)
            put("stale", provenance?.isStale)
            put("retrievedAt", provenance?.retrievedAt?.toString())
            put("sourceRevision", provenance?.sourceRevision)
            put("retrievedDate", provenance?.bundledDate?.toString())
        },
    )
}

private fun propertyInfo(value: TaigaApiProperty) =
    buildJsonObject {
        put("name", value.name)
        put("signature", value.signature.take(2_000))
        put("type", value.documentedType?.take(2_000))
        put("description", value.description?.take(500))
    }
