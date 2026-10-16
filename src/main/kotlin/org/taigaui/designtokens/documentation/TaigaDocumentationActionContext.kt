package org.taigaui.designtokens.documentation

import com.intellij.lang.ecmascript6.psi.ES6FromClause
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.angular2.entities.Angular2EntitiesProvider
import java.nio.file.Path

/** Cheap checks only: installed API is extracted again by Refresh, never inside a write command. */
internal class TaigaDocumentationActionContext(
    private val project: Project,
    private val document: Document,
    installedSources: List<Path> = emptyList(),
) {
    private val documentStamp = document.modificationStamp
    private val rootsStamp = ProjectRootManager.getInstance(project).modificationCount
    private val dependencies = captureDocumentationDependencies(project, document, installedSources)
    private val files = dependencies.files.map(::DocumentationFileStamp)
    private val unsaved = relevantUnsavedDocuments().associateWith(Document::getModificationStamp)
    private var changedOnDisk = false

    fun filesChanged(events: List<VFileEvent>) {
        if (events.any { dependencies.contains(it.path) }) changedOnDisk = true
    }

    fun isCurrent(): Boolean =
        !project.isDisposed &&
            document.modificationStamp == documentStamp &&
            ProjectRootManager.getInstance(project).modificationCount == rootsStamp &&
            !changedOnDisk &&
            files.all(DocumentationFileStamp::isCurrent) &&
            relevantUnsavedDocuments().all { unsaved[it] == it.modificationStamp } &&
            PsiDocumentManager.getInstance(project).uncommittedDocuments.none { pending ->
                pending === document ||
                    FileDocumentManager
                        .getInstance()
                        .getFile(pending)
                        ?.path
                        ?.let(dependencies::contains) == true
            }

    private fun relevantUnsavedDocuments(): List<Document> =
        FileDocumentManager.getInstance().unsavedDocuments.filter { pending ->
            FileDocumentManager
                .getInstance()
                .getFile(pending)
                ?.path
                ?.let(dependencies::contains) == true
        }
}

private class DocumentationFileStamp(
    private val file: VirtualFile,
) {
    private val vfsStamp = file.modificationStamp
    private val document = FileDocumentManager.getInstance().getCachedDocument(file)
    private val documentStamp = document?.modificationStamp

    fun isCurrent(): Boolean =
        file.isValid &&
            file.modificationStamp == vfsStamp &&
            (document == null || document.modificationStamp == documentStamp)
}

private data class DocumentationDependencies(
    val files: Set<VirtualFile>,
    val roots: Set<String>,
    val configurationPaths: Set<String> = emptySet(),
) {
    fun contains(path: String): Boolean =
        path in configurationPaths || files.any { it.path == path } || roots.any { path == it || path.startsWith("$it/") }
}

/** Bounded local import graph plus installed package roots; never scan the project on a UI callback. */
private fun captureDocumentationDependencies(
    project: Project,
    document: Document,
    installedSources: List<Path>,
): DocumentationDependencies {
    val file =
        PsiDocumentManager.getInstance(project).getPsiFile(document)
            ?: return DocumentationDependencies(emptySet(), emptySet())
    val component = Angular2EntitiesProvider.findTemplateComponent(file)
    val queue = ArrayDeque<PsiFile>()
    queue.add(file)
    component?.sourceElement?.containingFile?.let(queue::add)
    installedSources.mapNotNull { findDocumentationFile(project, it) }
        .mapNotNull { com.intellij.psi.PsiManager.getInstance(project).findFile(it) }.forEach(queue::add)
    val files = linkedSetOf<VirtualFile>()
    val roots = linkedSetOf<String>()
    val configurationPaths = linkedSetOf<String>()
    while (queue.isNotEmpty() && files.size < MAX_CONTEXT_FILES) {
        val current = queue.removeFirst()
        val virtual = current.originalFile.virtualFile?.takeIf { files.add(it) } ?: continue
        val packageRoot = virtual.path.installedPackageRoot()
        if (packageRoot != null) {
            roots.add(packageRoot)
        } else {
            PsiTreeUtil.findChildrenOfType(current, ES6FromClause::class.java)
                .take(MAX_CONTEXT_IMPORTS)
                .forEach { from ->
                    from.resolveReferencedElements().mapNotNull { it.containingFile }.forEach(queue::add)
                }
        }
        generateSequence(virtual.parent) { it.parent }.forEach { directory ->
            CONTEXT_CONFIG_FILES.forEach { name ->
                configurationPaths.add(directory.path + "/" + name)
                directory.findChild(name)?.let(files::add)
            }
        }
    }
    // Incomplete graphs retain a conservative local boundary instead of authorizing stale writes.
    if (queue.isNotEmpty()) project.basePath?.let(roots::add)
    return DocumentationDependencies(files, roots, configurationPaths)
}

private fun String.installedPackageRoot(): String? =
    Regex("^(.*?/node_modules/(?:@[^/]+/)?[^/]+)(?:/|$)").find(this)?.groupValues?.get(1)

private const val MAX_CONTEXT_FILES = 128
private const val MAX_CONTEXT_IMPORTS = 64
private val CONTEXT_CONFIG_FILES =
    listOf(
        "package.json",
        "tsconfig.json",
        "tsconfig.app.json",
        "angular.json",
        "project.json",
        "pnpm-lock.yaml",
        "package-lock.json",
        "yarn.lock",
        ".pnp.cjs",
        ".pnp.data.json",
    )

/** Tracks the opening tag so Refresh survives edits before it and a binding rename. */
internal class TaigaDocumentationRefreshTarget(
    private val document: Document,
    request: TaigaDocumentationRequest,
) : Disposable {
    private val element =
        when (request) {
            is TaigaDocumentationRequest.Entity -> request.element
            is TaigaDocumentationRequest.Member -> request.element
        }
    private val marker =
        document.createRangeMarker(
            element?.startOffset ?: request.startOffset,
            element?.endOffset ?: request.endOffset,
        )
    var name: String =
        (request as? TaigaDocumentationRequest.Member)?.name
            ?: document.charsSequence
                .subSequence(
                    request.startOffset,
                    request.endOffset,
                ).toString()
                .trim('[', ']', '(', ')')

    @Suppress("ReturnCount")
    fun offset(): Int? {
        if (!marker.isValid) return null
        if (element == null) return marker.startOffset
        val text = document.charsSequence.subSequence(marker.startOffset, marker.endOffset).toString()
        val boundary = text.getOrNull(name.length + 1)
        val endsTagName = boundary?.isWhitespace() == true || boundary == '>' || boundary == '/'
        if (text.startsWith("<$name") && endsTagName) {
            return marker.startOffset + 1
        }
        val attribute = Regex("(?:^|\\s)([\\[(]*${Regex.escape(name)}[\\])]*)(?=\\s*=|\\s|/?>)").find(text)
        return attribute
            ?.groups
            ?.get(1)
            ?.range
            ?.first
            ?.let { marker.startOffset + it + if (text[it] == '[') 1 else 0 }
    }

    override fun dispose() = marker.dispose()
}

internal const val STALE_DOCUMENTATION_MESSAGE = "Code changed. Refresh this card before applying changes."
