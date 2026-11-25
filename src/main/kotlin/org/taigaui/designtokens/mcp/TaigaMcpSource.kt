package org.taigaui.designtokens.mcp

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.taigaui.designtokens.project.isSelectableProjectDirectory
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal suspend fun taigaSourceResult(
    source: String,
    action: suspend (Project, VirtualFile) -> JsonObject,
): McpToolCallResult {
    val project = currentCoroutineContext().project

    return try {
        withContext(Dispatchers.IO) { action(project, project.taigaMcpSource(source)).toMcpResult() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IllegalStateException) {
        McpToolCallResult.error(error.message ?: "Could not inspect this Taiga UI workspace.")
    } catch (error: IOException) {
        McpToolCallResult.error(error.message ?: "Could not read this Taiga UI workspace.")
    }
}

internal suspend fun Project.taigaMcpSource(source: String): VirtualFile {
    val root = Path.of(checkNotNull(basePath) { "The open project has no local root." }).normalize()
    val path =
        try {
            Path.of(source)
        } catch (error: InvalidPathException) {
            throw IllegalStateException("Invalid project-relative path.", error)
        }

    check(source.isNotBlank() && !path.isAbsolute && path.none { it.toString() == ".." }) {
        "Use a path inside the IDE project, relative to its root."
    }
    val selected = root.resolve(path).normalize()
    check(selected.startsWith(root)) { "Use a path inside the IDE project, relative to its root." }

    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(selected)
    val valid =
        readAction {
            file != null &&
                !file.`is`(VFileProperty.SYMLINK) &&
                (if (file.isDirectory) file else file.parent)?.isSelectableProjectDirectory(this) == true
        }

    return checkNotNull(
        file.takeIf {
            valid
        },
    ) { "Select a local project file or directory outside dependencies and symlinks." }
}

internal fun JsonObject.toMcpResult(): McpToolCallResult = McpToolCallResult.text(toString(), this)

internal fun Iterable<String>.jsonStrings(): JsonArray = jsonArray(::JsonPrimitive)

internal fun <T> Iterable<T>.jsonArray(transform: (T) -> JsonElement): JsonArray = JsonArray(map(transform))

internal const val TAIGA_SOURCE =
    "Project-relative file or directory. Use '.' for the root or a source file in the desired monorepo app."
internal const val TAIGA_SEARCH_LIMIT = "Maximum number of matches. Defaults to 20 and must be between 1 and 50."

internal fun <T> List<T>.limitedJson(
    limit: Int,
    transform: (T) -> JsonElement,
): JsonArray {
    check(limit in 1..50) { "The result limit must be between 1 and 50." }

    return take(limit).jsonArray(transform)
}
