package org.taigaui.designtokens.completion

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.taigaui.designtokens.project.DesignTokenIndexService
import org.taigaui.designtokens.psi.GlobalDesignTokenContext
import org.taigaui.designtokens.psi.PsiDesignTokenSourceExtractor
import org.taigaui.designtokens.resolution.CssLiteralColor
import org.taigaui.designtokens.resolution.matchColors
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
internal class DesignTokenColorLookup(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    private val index = project.service<DesignTokenIndexService>()
    private val entries = ConcurrentHashMap<VirtualFile, Entry>()

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val file = FileDocumentManager.getInstance().getFile(event.document) ?: return

                    if (entries.containsKey(file)) warmup(file)
                }
            },
            project,
        )
    }

    fun matches(
        file: VirtualFile,
        color: CssLiteralColor,
    ): List<String> {
        val entry = entries[file]

        if (entry?.revision == index.modificationCount) return entry.colors?.get(color).orEmpty()

        warmup(file)

        return emptyList()
    }

    fun warmup(file: VirtualFile) {
        if (!file.isValid || !file.isLocalStylesheet()) return

        val entry = Entry(index.modificationCount)
        val current = entries.compute(file) { _, active -> active?.takeIf { it.revision >= entry.revision } ?: entry }

        if (current !== entry) return

        scope.launch(Dispatchers.IO) {
            try {
                delay(100)

                if (entries[file] === entry) entry.colors = build(file)
            } finally {
                if (entry.colors == null) entries.remove(file, entry)
            }
        }
    }

    fun forget(file: VirtualFile) {
        entries.remove(file)
    }

    private fun build(file: VirtualFile): Map<CssLiteralColor, List<String>> {
        val path = Path.of(file.path)
        val snapshot = index.resolutionSnapshot(path)
        val mergedIndex = snapshot.mergedIndex
        val resolver = snapshot.resolver

        if (mergedIndex == null || resolver == null) return emptyMap()

        val extractor = PsiDesignTokenSourceExtractor(project)
        val localNames =
            ReadAction
                .nonBlocking<Set<String>> {
                    PsiManager
                        .getInstance(project)
                        .findFile(file)
                        ?.let { psi -> extractor.extract(psi, path) }
                        .orEmpty()
                        .filterNot { declaration -> GlobalDesignTokenContext.isGlobal(declaration.selectorChain) }
                        .map { declaration -> declaration.name }
                        .toSet()
                }.withDocumentsCommitted(project)
                .expireWith(project)
                .executeSynchronously()

        return mergedIndex.matchColors(
            resolver = resolver,
            names = snapshot.tokenNames.filter { name -> snapshot.deprecationFor(name) == null },
            selectorLocalNames = localNames,
        )
    }

    private class Entry(
        val revision: Long,
    ) {
        @Volatile var colors: Map<CssLiteralColor, List<String>>? = null
    }
}

internal class DesignTokenColorEditorListener : FileEditorManagerListener {
    override fun fileOpened(
        source: FileEditorManager,
        file: VirtualFile,
    ) {
        if (file.isLocalStylesheet()) source.project.service<DesignTokenColorLookup>().warmup(file)
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        event.newFile?.let { file -> fileOpened(event.manager, file) }
    }

    override fun fileClosed(
        source: FileEditorManager,
        file: VirtualFile,
    ) {
        if (file.isLocalStylesheet()) source.project.service<DesignTokenColorLookup>().forget(file)
    }
}

private fun VirtualFile.isLocalStylesheet(): Boolean = isInLocalFileSystem && extension?.lowercase() in STYLESHEETS

private val STYLESHEETS = setOf("css", "less", "scss")
