package org.taigaui.designtokens.documentation

import org.taigaui.designtokens.index.DesignTokenOrigin
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal object DesignTokenDescriptionExtractor {
    private val sourceCache = ConcurrentHashMap<Path, SourceSnapshot>()

    fun extract(origins: Collection<DesignTokenOrigin>): String? =
        origins
            .asSequence()
            .mapNotNull(::extract)
            .distinct()
            .toList()
            .singleOrNull()

    internal fun extract(
        lines: List<String>,
        declarationLine: Int,
    ): String? {
        val declarationIndex = declarationLine - 1

        if (declarationIndex !in lines.indices) {
            return null
        }

        return findTrailingComment(lines, declarationIndex)
            ?: findLeadingComment(lines, declarationIndex)
    }

    private fun extract(origin: DesignTokenOrigin): String? =
        runCatching {
            val source = readSource(origin.sourceFile)

            extract(source.lines, origin.line)
                ?.takeIf(String::isUsefulDescription)
        }.getOrNull()

    private fun readSource(path: Path): SourceSnapshot {
        val modifiedAt = Files.getLastModifiedTime(path).toMillis()

        return sourceCache.compute(path) { _, cached ->
            cached
                ?.takeIf { source -> source.modifiedAt == modifiedAt }
                ?: SourceSnapshot(
                    modifiedAt = modifiedAt,
                    lines = Files.readAllLines(path),
                )
        }
    }

    private fun findTrailingComment(
        lines: List<String>,
        declarationIndex: Int,
    ): String? {
        val lastLine = minOf(lines.lastIndex, declarationIndex + MAX_DECLARATION_LINES)

        for (lineIndex in declarationIndex..lastLine) {
            val semicolonIndex = lines[lineIndex].indexOf(';')

            if (semicolonIndex >= 0) {
                return findCommentStartingAt(lines, lineIndex, semicolonIndex + 1)
                    ?.normalizeComment()
                    ?.takeIf(String::isUsefulDescription)
            }
        }

        return null
    }

    private fun findLeadingComment(
        lines: List<String>,
        declarationIndex: Int,
    ): String? {
        val previousIndex = declarationIndex - 1

        if (previousIndex !in lines.indices || lines[previousIndex].isBlank()) {
            return null
        }

        val previousLine = lines[previousIndex].trim()
        val rawComment =
            when {
                previousLine.startsWith("//") -> collectLineComment(lines, previousIndex)
                previousLine.endsWith("*/") -> collectBlockComment(lines, previousIndex)
                else -> null
            }

        return rawComment
            ?.normalizeComment()
            ?.takeIf(String::isUsefulDescription)
    }

    private fun findCommentStartingAt(
        lines: List<String>,
        lineIndex: Int,
        column: Int,
    ): String? {
        val suffix = lines[lineIndex].substring(column)
        val blockStart = suffix.indexOf("/*")
        val lineStart = suffix.indexOf("//")

        return when {
            blockStart >= 0 && (lineStart < 0 || blockStart < lineStart) ->
                collectTrailingBlockComment(lines, lineIndex, column + blockStart)

            lineStart >= 0 -> suffix.substring(lineStart)
            else -> null
        }
    }

    private fun collectTrailingBlockComment(
        lines: List<String>,
        startLine: Int,
        startColumn: Int,
    ): String? {
        val result = StringBuilder()

        for (lineIndex in startLine..lines.lastIndex) {
            val line =
                if (lineIndex == startLine) {
                    lines[lineIndex].substring(startColumn)
                } else {
                    lines[lineIndex]
                }
            val endIndex = line.indexOf("*/")

            if (result.isNotEmpty()) {
                result.append('\n')
            }

            if (endIndex >= 0) {
                result.append(line.substring(0, endIndex + BLOCK_COMMENT_END.length))

                return result.toString()
            }

            result.append(line)
        }

        return null
    }

    private fun collectLineComment(
        lines: List<String>,
        endLine: Int,
    ): String {
        val comments = ArrayDeque<String>()
        var lineIndex = endLine

        while (lineIndex >= 0) {
            val line = lines[lineIndex].trim()

            if (!line.startsWith("//")) {
                break
            }

            comments.addFirst(line)
            lineIndex--
        }

        return comments.joinToString(separator = "\n")
    }

    private fun collectBlockComment(
        lines: List<String>,
        endLine: Int,
    ): String? {
        val comments = ArrayDeque<String>()
        var lineIndex = endLine

        while (lineIndex >= 0) {
            val line = lines[lineIndex]
            comments.addFirst(line)

            if (line.contains("/*")) {
                val prefix = line.substringBefore("/*")
                val suffix = lines[endLine].substringAfter("*/", missingDelimiterValue = "")

                return if (prefix.isBlank() && suffix.isBlank()) {
                    comments.joinToString(separator = "\n")
                } else {
                    null
                }
            }

            lineIndex--
        }

        return null
    }

    private data class SourceSnapshot(
        val modifiedAt: Long,
        val lines: List<String>,
    )
}

private fun String.normalizeComment(): String {
    val normalizedLines =
        lineSequence().map { line ->
            var normalized = line.trim()

            normalized = normalized.removePrefix("/*")
            normalized = normalized.removePrefix("/**")
            normalized = normalized.removePrefix("//")
            normalized = normalized.removeSuffix("*/")
            normalized = normalized.trim()
            normalized = normalized.removePrefix("*")

            normalized.trim()
        }
    val content = normalizedLines.filter(String::isNotEmpty).joinToString(separator = " ")

    return content.replace(WHITESPACE, " ").trim()
}

private fun String.isUsefulDescription(): Boolean {
    val normalized = lowercase()

    return isNotBlank() && IGNORED_COMMENT_PREFIXES.none(normalized::startsWith)
}

private val WHITESPACE = Regex("\\s+")
private val IGNORED_COMMENT_PREFIXES =
    listOf(
        "stylelint",
        "prettier",
        "noinspection",
        "language=",
        "region",
        "endregion",
        "todo",
        "fixme",
    )
private const val MAX_DECLARATION_LINES = 12
private const val BLOCK_COMMENT_END = "*/"
