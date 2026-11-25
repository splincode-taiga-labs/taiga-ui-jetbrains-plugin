package org.taigaui.designtokens.mcp

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.taigaui.designtokens.completion.FolderColorPreview
import org.taigaui.designtokens.completion.FolderColorPreviewDialog
import org.taigaui.designtokens.completion.FolderColorReplacement
import java.security.MessageDigest
import java.util.HexFormat

@Suppress("FunctionName", "unused")
class TaigaFolderColorToolset : McpToolset {
    @McpTool
    @McpDescription(
        "Preview which CSS, Less and SCSS files would change in a folder when replacing colors with Taiga UI tokens.",
    )
    suspend fun taiga_ui_preview_folder_colors(
        @McpDescription(COLOR_DIRECTORY) directory: String,
    ): McpToolCallResult =
        taigaSourceResult(directory) { project, folder ->
            val previews = scanFolderColors(project, folder).previews

            buildJsonObject {
                put("revision", folderColorRevision(folder, previews))
                put("totalFiles", previews.size)
                put("files", previews.take(50).map(FolderColorPreview::relativePath).jsonStrings())
            }
        }

    @McpTool
    @McpDescription(
        "Apply the folder color replacements after the user reviews the exact diff and approves it in WebStorm. " +
            "The changes can be undone together in the IDE. Preview first.",
    )
    suspend fun taiga_ui_apply_folder_colors(
        @McpDescription(COLOR_DIRECTORY) directory: String,
        @McpDescription("Revision returned by taiga_ui_preview_folder_colors for the same folder.") revision: String,
    ): McpToolCallResult =
        taigaSourceResult(directory) { project, folder ->
            val replacement = scanFolderColors(project, folder)
            val previews = replacement.previews
            check(
                folderColorRevision(folder, previews) == revision,
            ) { "Stylesheets or tokens changed. Preview the folder again." }

            when {
                previews.isEmpty() -> folderColorResult("no_changes", "No stylesheet colors to update.")
                ApplicationManager.getApplication().isHeadlessEnvironment ->
                    folderColorResult("confirmation_required", "Confirm the replacements in an open WebStorm window.")
                else -> confirmAndApply(project, replacement)
            }
        }
}

private suspend fun scanFolderColors(
    project: Project,
    folder: VirtualFile,
): FolderColorReplacement {
    check(folder.isDirectory) { "Select a project directory for color replacements." }

    return coroutineToIndicator { indicator: ProgressIndicator ->
        FolderColorReplacement.scan(
            project,
            folder,
            indicator,
        )
    }
}

private suspend fun confirmAndApply(
    project: Project,
    replacement: FolderColorReplacement,
) = withContext(Dispatchers.EDT) {
    if (project.isDisposed || !FolderColorPreviewDialog(project, replacement.previews).showAndGet()) {
        return@withContext folderColorResult("cancelled", "The folder colors were not changed.")
    }
    currentCoroutineContext().ensureActive()
    check(!project.isDisposed) { "The project is closed." }
    val result = replacement.apply(project)
    check(result.applied) { result.message }

    folderColorResult("applied", result.message)
}

private fun folderColorRevision(
    folder: VirtualFile,
    previews: List<FolderColorPreview>,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.addField(folder.path)
    for (preview in previews) {
        digest.addField(preview.relativePath)
        digest.addField(preview.original)
        digest.addField(preview.proposed)
    }

    return HexFormat.of().formatHex(digest.digest())
}

private fun MessageDigest.addField(value: String) {
    val bytes = value.toByteArray()
    update("${bytes.size}:".toByteArray())
    update(bytes)
}

private fun folderColorResult(
    status: String,
    message: String,
) = buildJsonObject {
    put("status", status)
    put("message", message)
}

private const val COLOR_DIRECTORY = "Folder relative to the open IDE project root. Use '.' for the root."
