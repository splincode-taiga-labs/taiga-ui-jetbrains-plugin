package org.taigaui.designtokens.documentation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Prefer the project's installed Prettier, never install or bundle it.
 * Invalid/unsafe output and missing Node.js fall back to the built-in formatter.
 */
internal class TaigaDocumentationExamplePrettier(
    private val commandFor: (Path) -> List<String>? = ::findDocumentationPrettier,
    private val run: (List<String>, String) -> String? = ::runDocumentationPrettier,
) {
    private val cache = ConcurrentHashMap<Triple<Path, String, String>, String>()

    fun format(
        code: String,
        sourceFile: Path,
    ): String {
        if (code.length > MAX_PRETTIER_INPUT) return code
        val command = commandFor(sourceFile) ?: return formattedDocumentationExample(code)
        val key = Triple(sourceFile.toAbsolutePath().normalize().parent, command.joinToString("\u0000"), code)
        if (cache.size >= MAX_FORMATTED_EXAMPLES) cache.clear()
        return cache.computeIfAbsent(key) {
            run(command, code)
                ?.trimEnd()
                ?.takeIf { text ->
                    text.isNotBlank() &&
                        text.length <= MAX_PRETTIER_OUTPUT &&
                        documentationExamplePreservesContent(code, text)
                } ?: formattedDocumentationExample(code)
        }
    }
}

private fun findDocumentationPrettier(sourceFile: Path): List<String>? {
    var directory = sourceFile.toAbsolutePath().normalize().parent

    while (directory != null) {
        val root = directory.resolve("node_modules/prettier")
        val cli =
            listOf(root.resolve("bin/prettier.cjs"), root.resolve("bin-prettier.js"))
                .firstOrNull(Files::isRegularFile)
        if (cli != null) {
            return listOf(
                "node",
                cli.toString(),
                "--parser",
                "angular",
                "--stdin-filepath",
                sourceFile.resolveSibling("TaigaExample.component.html").toString(),
                "--print-width",
                "100",
                "--tab-width",
                "4",
                "--single-attribute-per-line",
            )
        }
        directory = directory.parent
    }
    return null
}

private fun runDocumentationPrettier(
    command: List<String>,
    code: String,
): String? =
    runCatching {
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(code) }
            if (!process.waitFor(PRETTIER_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
                null
            } else {
                process.inputStream
                    .readNBytes(MAX_PRETTIER_OUTPUT + 1)
                    .toString(StandardCharsets.UTF_8)
                    .takeIf { it.length <= MAX_PRETTIER_OUTPUT }
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
            process.errorStream.close()
        }
    }.getOrNull()

private const val MAX_PRETTIER_INPUT = 4_000
private const val MAX_PRETTIER_OUTPUT = 12_000
private const val MAX_FORMATTED_EXAMPLES = 128
private const val PRETTIER_TIMEOUT_SECONDS = 2L
