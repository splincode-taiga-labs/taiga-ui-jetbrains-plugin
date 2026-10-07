package org.taigaui.designtokens.documentation

import com.intellij.openapi.application.PathManager
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class TaigaDocsCache(
    private val cacheRoot: Path =
        Path.of(
            PathManager.getSystemPath(),
            "taiga-ui-companion",
            "documentation",
        ),
    private val moveFile: (Path, Path, Array<CopyOption>) -> Path = { source, target, options ->
        Files.move(source, target, *options)
    },
) {
    fun read(source: TaigaDocsSource): String? {
        val path = cacheFile(source)

        return runCatching {
            path
                .takeIf(Files::isRegularFile)
                ?.let { file -> Files.readString(file, StandardCharsets.UTF_8) }
                ?.takeIf(String::isNotBlank)
        }.getOrNull()
    }

    fun write(
        source: TaigaDocsSource,
        content: String,
    ): Boolean {
        if (content.isBlank()) {
            return false
        }

        return runCatching {
            Files.createDirectories(cacheRoot)
            val target = cacheFile(source)
            val temporary = Files.createTempFile(cacheRoot, source.cacheKey, ".tmp")

            try {
                Files.writeString(temporary, content, StandardCharsets.UTF_8)
                moveIntoPlace(temporary, target)
            } finally {
                Files.deleteIfExists(temporary)
            }
            true
        }.getOrDefault(false)
    }

    fun invalidate(source: TaigaDocsSource): Boolean =
        runCatching { Files.deleteIfExists(cacheFile(source)) }.getOrDefault(false)

    private fun cacheFile(source: TaigaDocsSource): Path = cacheRoot.resolve("${source.cacheKey}-llms-full.txt")

    private fun moveIntoPlace(
        source: Path,
        target: Path,
    ) {
        try {
            moveFile(
                source,
                target,
                arrayOf(
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                ),
            )
        } catch (_: AtomicMoveNotSupportedException) {
            moveFile(
                source,
                target,
                arrayOf(StandardCopyOption.REPLACE_EXISTING),
            )
        }
    }
}
