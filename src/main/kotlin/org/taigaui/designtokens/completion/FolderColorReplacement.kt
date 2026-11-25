package org.taigaui.designtokens.completion

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.util.PsiTreeUtil
import org.taigaui.designtokens.project.DesignTokenIndexService
import org.taigaui.designtokens.resolution.CssLiteralColor

internal const val FOLDER_COLOR_REPLACEMENT_TITLE = "Replace Colors with Taiga UI Tokens"

internal class FolderColorReplacement private constructor(
    private val tokenRevision: Long,
    private val rootsRevision: Long,
    private val files: List<FileColorEdits>,
) {
    val previews: List<FolderColorPreview> = files.map(FileColorEdits::preview)

    fun apply(project: Project): FolderColorApplyResult {
        if (files.isEmpty()) return FolderColorApplyResult(true, "No stylesheet colors to update.")

        val applied =
            WriteCommandAction
                .writeCommandAction(project)
                .withName(FOLDER_COLOR_REPLACEMENT_TITLE)
                .withGlobalUndo()
                .compute<Boolean, RuntimeException> {
                    isCurrent(project).also { current ->
                        if (current) files.forEach(FileColorEdits::apply)
                    }
                }

        return if (applied) {
            FolderColorApplyResult(true, summary())
        } else {
            FolderColorApplyResult(false, "Stylesheets or tokens changed since the scan started. Run the action again.")
        }
    }

    private fun summary(): String {
        val replaced = files.sumOf { file -> file.edits.count { it.replaced } }
        val suggested = files.sumOf { file -> file.edits.count { it.suggested } }

        return "Replaced $replaced colors and added $suggested suggestions in ${files.size} files."
    }

    private fun isCurrent(project: Project): Boolean =
        tokenRevision == project.service<DesignTokenIndexService>().modificationCount &&
            rootsRevision == ProjectRootManager.getInstance(project).modificationCount &&
            files.all { file ->
                file.virtualFile.isValid &&
                    file.virtualFile.isWritable &&
                    file.virtualFile.path == file.path &&
                    file.document.isWritable &&
                    file.document.modificationStamp == file.stamp
            }

    companion object {
        fun scan(
            project: Project,
            directory: VirtualFile,
            indicator: ProgressIndicator,
        ): FolderColorReplacement {
            val tokenRevision = project.service<DesignTokenIndexService>().modificationCount
            val rootsRevision = ProjectRootManager.getInstance(project).modificationCount
            val lookup = project.service<DesignTokenColorLookup>()
            val stylesheets = project.stylesheetsUnder(directory).sortedBy(VirtualFile::getPath)

            indicator.isIndeterminate = false

            val files =
                stylesheets.mapIndexedNotNull { index, file ->
                    indicator.checkCanceled()
                    indicator.text2 = file.name
                    indicator.fraction = (index + 1).toDouble() / stylesheets.size
                    val path = file.path
                    val source = project.readStylesheet(file) ?: return@mapIndexedNotNull null
                    val matches = lookup.buildMatches(file)
                    val edits =
                        source.colors
                            .mapNotNull { color -> source.edit(color, matches[color.color].orEmpty()) }
                            .sortedByDescending { it.range.startOffset }

                    if (edits.isEmpty()) return@mapIndexedNotNull null

                    val relativePath = VfsUtilCore.getRelativePath(file, directory) ?: file.name
                    val preview = source.preview(relativePath, edits)

                    FileColorEdits(file, path, source.document, source.stamp, edits, preview)
                }

            return FolderColorReplacement(tokenRevision, rootsRevision, files)
        }
    }
}

internal data class FolderColorPreview(
    val relativePath: String,
    val fileType: FileType,
    val original: String,
    val proposed: String,
)

internal data class FolderColorApplyResult(
    val applied: Boolean,
    val message: String,
)

private fun Project.stylesheetsUnder(directory: VirtualFile): List<VirtualFile> =
    ReadAction
        .nonBlocking<List<VirtualFile>> {
            val index = ProjectFileIndex.getInstance(this)

            buildList {
                VfsUtilCore.iterateChildrenRecursively(
                    directory,
                    { file ->
                        ProgressManager.checkCanceled()
                        file.name != "node_modules" && !file.`is`(VFileProperty.SYMLINK) && index.isInContent(file)
                    },
                    { file ->
                        if (!file.isDirectory && file.isWritable && file.isLocalStylesheet()) add(file)

                        true
                    },
                )
            }
        }.expireWith(this)
        .executeSynchronously()

private fun Project.readStylesheet(file: VirtualFile): StylesheetColors? =
    ReadAction
        .nonBlocking<StylesheetColors?> {
            val psi = PsiManager.getInstance(this).findFile(file) ?: return@nonBlocking null
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@nonBlocking null

            if (!document.isWritable) return@nonBlocking null

            val colors =
                PsiTreeUtil
                    .findChildrenOfType(psi, CssDeclaration::class.java)
                    .flatMap { declaration ->
                        ProgressManager.checkCanceled()
                        declaration.literalColorTerms().mapNotNull { term ->
                            CssLiteralColor.parse(term.text)?.let { color -> ColorOccurrence(term.textRange, color) }
                        }
                    }

            if (colors.isEmpty()) return@nonBlocking null

            StylesheetColors(document, document.modificationStamp, psi.fileType, colors)
        }.withDocumentsCommitted(this)
        .expireWith(this)
        .executeSynchronously()

private data class StylesheetColors(
    val document: Document,
    val stamp: Long,
    val fileType: FileType,
    val colors: List<ColorOccurrence>,
) {
    private val text = document.immutableCharSequence

    fun preview(
        relativePath: String,
        edits: List<ColorEdit>,
    ): FolderColorPreview {
        val proposed = StringBuilder(text)

        edits.forEach { edit ->
            proposed.replace(edit.range.startOffset, edit.range.endOffset, edit.replacement)
        }

        return FolderColorPreview(relativePath, fileType, text.toString(), proposed.toString())
    }

    fun edit(
        occurrence: ColorOccurrence,
        tokens: List<String>,
    ): ColorEdit? {
        val range = occurrence.range
        val existing = COLOR_SUGGESTION.matchAt(text, range.endOffset)
        val end = existing?.range?.last?.plus(1) ?: range.endOffset

        if (tokens.size == 1) {
            return ColorEdit(TextRange(range.startOffset, end), "var(${tokens.single()})", replaced = true)
        }

        val suggestion =
            if (tokens.isEmpty()) {
                ""
            } else {
                " /* Taiga UI: consider ${tokens.joinToString(" or ") { "var($it)" }} */"
            }

        return suggestion
            .takeUnless { it == existing?.value.orEmpty() }
            ?.let { ColorEdit(TextRange(range.endOffset, end), it, suggested = it.isNotEmpty()) }
    }
}

private data class ColorOccurrence(
    val range: TextRange,
    val color: CssLiteralColor,
)

private data class ColorEdit(
    val range: TextRange,
    val replacement: String,
    val replaced: Boolean = false,
    val suggested: Boolean = false,
)

private data class FileColorEdits(
    val virtualFile: VirtualFile,
    val path: String,
    val document: Document,
    val stamp: Long,
    val edits: List<ColorEdit>,
    val preview: FolderColorPreview,
) {
    fun apply() {
        edits.forEach { edit ->
            document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.replacement)
        }
    }
}

private val COLOR_SUGGESTION = Regex(""" /\* Taiga UI: consider var\(--tui-[\w-]+\)(?: or var\(--tui-[\w-]+\))* \*/""")
