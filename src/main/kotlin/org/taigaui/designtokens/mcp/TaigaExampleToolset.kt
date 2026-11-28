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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.taigaui.designtokens.documentation.TaigaDocsService
import org.taigaui.designtokens.documentation.TaigaDocsSnapshot
import org.taigaui.designtokens.documentation.TaigaEntityDoc
import org.taigaui.designtokens.documentation.discovery.TaigaApiCatalog
import org.taigaui.designtokens.imports.TaigaImportTarget
import org.taigaui.designtokens.imports.verifyTaigaExports

@Suppress("FunctionName", "unused")
class TaigaExampleToolset : McpToolset {
    @McpTool
    @McpDescription(
        "Read documented Taiga UI examples for up to ten API names in the selected app. " +
            "Use a source file to check documented exports against installed packages. " +
            "Pass an exact section ID to select an ambiguous match. Does not modify project files.",
    )
    suspend fun taiga_ui_get_examples(
        @McpDescription(TAIGA_SOURCE) source: String,
        @McpDescription(
            "1 to 10 titles, public symbols, Angular selectors, or exact section IDs, up to 150 characters each.",
        )
        names: List<String>,
    ): McpToolCallResult =
        taigaSourceResult(source) { project, file ->
            check(names.size in 1..10 && names.all { it.isNotBlank() && it.length <= 150 }) {
                "Provide 1 to 10 nonblank API names of at most 150 characters each."
            }
            val docs = project.service<TaigaDocsService>()
            val path = file.toNioPath()
            val snapshot = docs.snapshotFor(path)
            val catalog = snapshot?.let(::TaigaApiCatalog)
            val matches = names.map { findExample(it, snapshot, catalog) }
            val candidates =
                matches
                    .mapNotNull(ExampleMatch::entry)
                    .flatMap { it.importCandidates(snapshot?.projectContext?.installedPackages.orEmpty()) }
                    .distinct()
            val verified = verifyExports(project, file, candidates)
            val version = snapshot?.projectContext?.version ?: docs.detectContext(path)?.version

            buildJsonObject {
                put("installedVersion", version)
                putDocumentation(snapshot?.index)
                put("matched", matches.count { it.entry != null })
                put("results", matches.jsonArray { it.toJson(snapshot != null, verified) })
            }
        }
}

private data class ExampleMatch(
    val query: String,
    val entry: TaigaEntityDoc? = null,
    val suggestions: List<TaigaEntityDoc> = emptyList(),
) {
    fun toJson(
        hasDocumentation: Boolean,
        verified: Set<TaigaImportTarget>,
    ) = buildJsonObject {
        put("query", query)
        put(
            "status",
            when {
                entry != null -> "available"
                !hasDocumentation -> "documentation_unavailable"
                suggestions.isNotEmpty() -> "choose_section"
                else -> "not_found"
            },
        )
        entry?.let { section ->
            put("sectionId", section.sectionId)
            put("title", section.title)
            put("documentationUrl", section.documentationUri.toString())
            put("example", section.example?.code?.take(6_000))
            put("exampleLanguage", section.example?.language)
            put("exampleTruncated", (section.example?.code?.length ?: 0) > 6_000)
            put(
                "verifiedExports",
                section.importCandidates(section.packageNames).filter(verified::contains).jsonArray { target ->
                    buildJsonObject {
                        put("symbol", target.symbol)
                        put("package", target.packageName)
                    }
                },
            )
        }
        if (suggestions.isNotEmpty()) {
            put(
                "suggestions",
                suggestions.jsonArray { section ->
                    buildJsonObject {
                        put("sectionId", section.sectionId)
                        put("title", section.title)
                        put("packages", section.packageNames.jsonStrings())
                    }
                },
            )
        }
    }
}

private fun findExample(
    query: String,
    snapshot: TaigaDocsSnapshot?,
    catalog: TaigaApiCatalog?,
): ExampleMatch {
    val name = query.trim()
    snapshot?.findBySectionId(name)?.let { return ExampleMatch(query, it) }
    val matches = catalog?.search(name).orEmpty()
    val best = matches.firstOrNull()

    return if (best?.isExact == true && matches.getOrNull(1)?.weight != best.weight) {
        ExampleMatch(query, best.entry.entity)
    } else {
        ExampleMatch(query, suggestions = matches.take(5).map { it.entry.entity })
    }
}

private fun TaigaEntityDoc.importCandidates(installed: Set<String>): List<TaigaImportTarget> =
    packageNames
        .filter(installed::contains)
        .flatMap { name -> publicSymbols.map { TaigaImportTarget(it, name) } }

private fun verifyExports(
    project: Project,
    file: VirtualFile,
    candidates: List<TaigaImportTarget>,
): Set<TaigaImportTarget> {
    if (candidates.isEmpty() || file.isDirectory) return emptySet()

    return ReadAction
        .nonBlocking<Set<TaigaImportTarget>> {
            PsiManager
                .getInstance(project)
                .findFile(file)
                ?.verifyTaigaExports(candidates)
                .orEmpty()
        }.withDocumentsCommitted(project)
        .inSmartMode(project)
        .expireWith(project)
        .executeSynchronously()
}
