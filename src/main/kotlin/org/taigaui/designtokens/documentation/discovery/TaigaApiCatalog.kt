package org.taigaui.designtokens.documentation.discovery

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.taigaui.designtokens.documentation.TaigaDocKind
import org.taigaui.designtokens.documentation.TaigaDocsService
import org.taigaui.designtokens.documentation.TaigaDocsSnapshot
import org.taigaui.designtokens.documentation.TaigaEntityDoc
import java.nio.file.Path
import java.util.Locale

internal data class TaigaApiEntry(
    val entity: TaigaEntityDoc,
    val packages: List<String>,
) {
    val kindLabel: String =
        entity.kind.name
            .lowercase(Locale.ROOT)
            .replaceFirstChar(Char::uppercase)
    val metadata: String = (listOf(kindLabel) + packages).joinToString(" · ")
    val summary: String? =
        entity.description
            ?.let { description ->
                if (description.length > MAX_SUMMARY_LENGTH) {
                    description.take(MAX_SUMMARY_LENGTH - 1).trimEnd() + "…"
                } else {
                    description
                }
            }

    val titleKey: String = entity.title.searchKey()
    val identifierKeys: Set<String> = (entity.publicSymbols + entity.selectors).mapTo(mutableSetOf(), String::searchKey)

    private companion object {
        const val MAX_SUMMARY_LENGTH = 140
    }
}

internal data class TaigaApiMatch(
    val entry: TaigaApiEntry,
    val weight: Int,
)

internal class TaigaApiCatalog(
    snapshot: TaigaDocsSnapshot,
) {
    val version: String = snapshot.projectContext.version
    val index = snapshot.index
    private val entries: List<TaigaApiEntry> =
        snapshot.entities
            .distinctBy(TaigaEntityDoc::sectionId)
            .map { entity ->
                val packages = entity.packageNames.filter(snapshot.projectContext.installedPackages::contains)

                TaigaApiEntry(entity, packages.sorted())
            }.sortedWith(compareBy({ it.titleKey }, { it.entity.sectionId }))

    fun search(
        query: String,
        checkCanceled: () -> Unit = {},
    ): List<TaigaApiMatch> {
        val key = query.searchKey()

        if (key.isEmpty() && query.isNotBlank()) {
            return emptyList()
        }

        return entries
            .mapNotNull { entry ->
                checkCanceled()
                score(entry, key)?.let { TaigaApiMatch(entry, it) }
            }.sortedByDescending(TaigaApiMatch::weight)
    }

    private fun score(
        entry: TaigaApiEntry,
        query: String,
    ): Int? =
        when {
            query.isEmpty() -> 0
            query in entry.identifierKeys -> 100
            entry.titleKey == query -> 90
            entry.titleKey.startsWith(query) -> 80
            entry.identifierKeys.any { it.startsWith(query) } -> 70
            query in entry.titleKey -> 60
            entry.identifierKeys.any { query in it } -> 50
            query.length >= MIN_FUZZY_LENGTH && entry.titleKey.containsInOrder(query) -> 40
            query.length >= MIN_FUZZY_LENGTH && entry.identifierKeys.any { it.containsInOrder(query) } -> 30
            else -> null
        }

    companion object {
        suspend fun load(
            project: Project,
            source: Path,
            refresh: Boolean = false,
        ): TaigaApiCatalog? =
            withContext(Dispatchers.Default) {
                val docs = project.service<TaigaDocsService>()
                val snapshot = if (refresh) docs.refresh(source) else docs.snapshotFor(source)

                snapshot?.let(::TaigaApiCatalog)
            }
    }
}

internal val TaigaDocKind.categoryLabel: String
    get() =
        when (this) {
            TaigaDocKind.COMPONENT -> "Components"
            TaigaDocKind.DIRECTIVE -> "Directives"
            TaigaDocKind.PIPE -> "Pipes"
            TaigaDocKind.SERVICE -> "Services"
            TaigaDocKind.TYPE -> "Types"
            TaigaDocKind.TOKEN -> "Tokens"
            TaigaDocKind.UTILITY -> "Utilities"
            TaigaDocKind.CLASS -> "Classes"
            TaigaDocKind.UNKNOWN -> "Other"
        }

internal fun taigaApiSource(
    project: Project,
    file: VirtualFile? = FileEditorManager.getInstance(project).selectedFiles.firstOrNull(),
): Path? = file?.takeIf { it.isInLocalFileSystem }?.path?.let(Path::of) ?: project.basePath?.let(Path::of)

private fun String.searchKey(): String = lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

private fun String.containsInOrder(query: String): Boolean {
    var matched = 0

    for (character in this) {
        if (character == query[matched]) {
            matched++

            if (matched == query.length) {
                return true
            }
        }
    }

    return false
}

private const val MIN_FUZZY_LENGTH = 3
