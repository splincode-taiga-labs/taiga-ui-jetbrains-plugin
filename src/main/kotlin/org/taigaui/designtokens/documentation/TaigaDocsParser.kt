package org.taigaui.designtokens.documentation

@Suppress("TooManyFunctions")
internal class TaigaDocsParser {
    fun parse(
        source: TaigaDocsSource,
        content: String,
    ): TaigaDocsIndex? {
        val lines =
            content
                .removePrefix("\uFEFF")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
        val imports = parseImportMap(lines)
        val entities =
            topLevelSections(lines).map { section ->
                parseEntity(source, section, imports)
            }

        return entities.takeIf(List<TaigaEntityDoc>::isNotEmpty)?.let { TaigaDocsIndex(source, it) }
    }

    private fun parseImportMap(lines: List<String>): List<ImportEntry> {
        val start = lines.indexOfFirst { it.trim() == IMPORT_MAP_HEADING }

        if (start < 0) {
            return emptyList()
        }

        val end = ((start + 1) until lines.size).firstOrNull { lines[it].startsWith("# ") } ?: lines.size
        val result = mutableListOf<ImportEntry>()
        var packageName: String? = null
        var route: String? = null
        var slug: String? = null
        var index = start + 1

        while (index < end) {
            val line = lines[index].trim()

            when {
                line.startsWith("## @taiga-ui/") -> {
                    packageName = line.removePrefix("## ").trim()
                    route = null
                    slug = null
                }

                CATEGORY_HEADING.matches(line) -> {
                    route = CATEGORY_HEADING.matchEntire(line)!!.groupValues[1].canonicalRoute()
                    slug = null
                }

                line.startsWith("### ") -> slug = line.removePrefix("### ").canonicalSlug()
                line.startsWith(CODE_FENCE) && packageName != null && route != null && slug != null -> {
                    val block = mutableListOf<String>()

                    index++
                    while (index < end && !lines[index].trim().startsWith(CODE_FENCE)) {
                        block += lines[index++]
                    }

                    val symbols = EXPORT_SYMBOL.findAll(block.joinToString("\n")).map { it.value }.toSet()

                    if (symbols.isNotEmpty()) {
                        result += ImportEntry(packageName, route, slug, symbols)
                    }
                }
            }

            index++
        }

        return result
    }

    private fun topLevelSections(lines: List<String>): List<DocSection> {
        val starts = lines.indices.filter { ENTITY_HEADING.matches(lines[it].trim()) }

        return starts.map { start ->
            val match = ENTITY_HEADING.matchEntire(lines[start].trim())!!
            val end = ((start + 1) until lines.size).firstOrNull { lines[it].startsWith("# ") } ?: lines.size

            DocSection(
                route = match.groupValues[1].canonicalRoute(),
                title = match.groupValues[2].trim(),
                body = lines.subList(start + 1, end),
            )
        }
    }

    private fun parseEntity(
        source: TaigaDocsSource,
        section: DocSection,
        imports: List<ImportEntry>,
    ): TaigaEntityDoc {
        val metadata = parseMetadata(section.body)
        val slug = section.title.canonicalSlug()
        val matchingImports =
            imports.filter { entry ->
                entry.route == section.route &&
                    entry.slug == slug &&
                    (metadata.packageNames.isEmpty() || entry.packageName in metadata.packageNames)
            }
        val packageNames =
            metadata.packageNames.ifEmpty {
                matchingImports.mapTo(linkedSetOf(), ImportEntry::packageName)
            }
        val publicSymbols = matchingImports.flatMap(ImportEntry::symbols).toSet()
        val example = parseExample(section.body)
        val sectionId = section.route + "/" + slug

        return TaigaEntityDoc(
            sectionId = sectionId,
            title = section.title,
            packageNames = packageNames,
            kind = TaigaDocKind.from(section.route, section.title, metadata.type),
            version = metadata.version,
            description = parseDescription(section.body),
            publicSymbols = publicSymbols,
            selectors = inferSelectors(slug, publicSymbols, example),
            inputs = parseApiTable(section.body, API_INPUTS_HEADING),
            outputs = parseApiTable(section.body, API_OUTPUTS_HEADING),
            example = example,
            documentationUri = source.documentationUri(sectionId),
        )
    }

    private fun parseMetadata(body: List<String>): Metadata {
        val values =
            body
                .mapNotNull { line ->
                    METADATA_LINE.matchEntire(line.trim())?.let { match ->
                        match.groupValues[1].lowercase() to match.groupValues[2].trim().trim(BACKTICK)
                    }
                }.toMap()

        return Metadata(
            packageNames = values["package"].toPackageNames(),
            type = values["type"]?.lowercase()?.takeUnless { it == "null" },
            version = values["version"]?.takeUnless { it == "\u2014" },
        )
    }

    private fun parseDescription(body: List<String>): String? =
        body
            .dropWhile { it.isBlank() || METADATA_LINE.matches(it.trim()) }
            .takeWhile { !it.startsWith("### ") }
            .joinToString("\n")
            .trim()
            .replace(WHITESPACE, " ")
            .takeIf(String::isNotBlank)

    private fun parseExample(body: List<String>): TaigaExample? {
        val heading = body.indexOfFirst { it.trim() == EXAMPLE_HEADING }
        val fence =
            heading
                .takeIf { it >= 0 }
                ?.let { start ->
                    body.indices
                        .drop(start + 1)
                        .firstOrNull { body[it].trim().startsWith(CODE_FENCE) }
                }?.takeIf { start ->
                    body.subList(heading + 1, start).none { it.startsWith("### ") }
                }
        val bounds =
            fence?.let { start ->
                ((start + 1) until body.size)
                    .firstOrNull { body[it].trim().startsWith(CODE_FENCE) }
                    ?.let { end -> start to end }
            }

        return bounds?.let { (start, end) ->
            body
                .subList(start + 1, end)
                .joinToString("\n")
                .trimEnd()
                .takeIf(String::isNotBlank)
                ?.let { code ->
                    TaigaExample(
                        language =
                            body[start]
                                .trim()
                                .removePrefix(CODE_FENCE)
                                .trim()
                                .ifEmpty { null },
                        code = code,
                    )
                }
        }
    }

    private fun parseApiTable(
        body: List<String>,
        heading: String,
    ): List<TaigaApiProperty> {
        val start = body.indexOfFirst { it.trim() == heading }

        if (start < 0) {
            return emptyList()
        }

        return body
            .drop(start + 1)
            .takeWhile { !it.startsWith("### ") }
            .map(String::trim)
            .filter { it.startsWith('|') && it.endsWith('|') }
            .map { line ->
                line
                    .removePrefix("|")
                    .removeSuffix("|")
                    .replace("\\|", ESCAPED_PIPE)
                    .split('|')
                    .map { it.trim().replace(ESCAPED_PIPE, "|") }
            }.filter { it.size >= 3 }
            .filterNot { cells ->
                val first = cells[0].removeMarkdownCode()
                first.equals("Property", true) ||
                    first.equals("Event", true) ||
                    cells.all { TABLE_SEPARATOR.matches(it) }
            }.mapNotNull { cells ->
                val signature = cells[0].removeMarkdownCode()
                val name =
                    signature
                        .removeSurrounding("[(", ")]")
                        .removeSurrounding("[", "]")
                        .removeSurrounding("(", ")")

                name.takeIf(String::isNotBlank)?.let {
                    TaigaApiProperty(
                        name = name,
                        signature = signature,
                        documentedType = cells[1].removeMarkdownCode().takeUnless(::isEmptyTableValue),
                        description = cells[2].removeMarkdownCode().takeUnless(::isEmptyTableValue),
                    )
                }
            }
    }

    private fun inferSelectors(
        slug: String,
        publicSymbols: Set<String>,
        example: TaigaExample?,
    ): Set<String> {
        val code = example?.code ?: return emptySet()
        val candidates =
            buildSet {
                add("tui-$slug")
                publicSymbols
                    .filter { it.startsWith("Tui") && it.length > 3 && it[3].isUpperCase() }
                    .forEach { symbol ->
                        val name = symbol.removePrefix("Tui")

                        add("tui$name")
                        add("tui-" + name.camelToKebab())
                    }
            }

        return candidates.filterTo(linkedSetOf()) { selector ->
            code.contains("<$selector") ||
                Regex("(?<![A-Za-z0-9_-])" + Regex.escape(selector) + "(?=\\s|=|>|/|\\]|$)").containsMatchIn(code)
        }
    }

    private fun String.removeMarkdownCode(): String = trim().trim(BACKTICK)

    private fun isEmptyTableValue(value: String): Boolean = value.isBlank() || value == "\u2014" || value == "-"

    private fun String?.toPackageNames(): Set<String> =
        this
            ?.takeUnless { it.equals("null", true) }
            ?.split(PACKAGE_SEPARATOR)
            ?.mapNotNull { value ->
                value
                    .trim()
                    .let { if (it.startsWith("@taiga-ui/")) it else it.lowercase().replace('_', '-') }
                    .takeIf { it.startsWith("@taiga-ui/") || PACKAGE_NAME.matches(it) }
                    ?.let { if (it.startsWith("@taiga-ui/")) it else "@taiga-ui/$it" }
            }?.toSet()
            .orEmpty()

    private fun String.canonicalRoute(): String =
        trim().removeSuffix(":").lowercase().let { route ->
            when (route) {
                "component" -> "components"
                "directive" -> "directives"
                "pipe" -> "pipes"
                "service" -> "services"
                "type" -> "types"
                "token" -> "tokens"
                "utility", "utilitys", "utilities" -> "utils"
                "class", "classs" -> "classes"
                else -> route
            }
        }

    private fun String.canonicalSlug(): String = camelToKebab().replace(SLUG_SEPARATOR, "-").trim('-')

    private fun String.camelToKebab(): String =
        trim()
            .replace(LOWER_TO_UPPER, "$1-$2")
            .replace(ACRONYM_BOUNDARY, "$1-$2")
            .lowercase()

    private data class ImportEntry(
        val packageName: String,
        val route: String,
        val slug: String,
        val symbols: Set<String>,
    )

    private data class DocSection(
        val route: String,
        val title: String,
        val body: List<String>,
    )

    private data class Metadata(
        val packageNames: Set<String>,
        val type: String?,
        val version: String?,
    )

    private companion object {
        const val IMPORT_MAP_HEADING = "# Import Map - Package Exports Reference"
        const val EXAMPLE_HEADING = "### Example"
        const val API_INPUTS_HEADING = "### API - Inputs"
        const val API_OUTPUTS_HEADING = "### API - Outputs"
        const val ESCAPED_PIPE = "\u0000"
        const val BACKTICK = '\u0060'
        const val CODE_FENCE = "\u0060\u0060\u0060"
        val ENTITY_HEADING = Regex("^# ([A-Za-z][A-Za-z0-9-]*)/(.+?)\\s*$")
        val CATEGORY_HEADING = Regex("^\\*\\*([A-Za-z][A-Za-z ]+):\\*\\*$")
        val METADATA_LINE = Regex(
            "^- \\*\\*(Package|Type|Version)\\*\\*:\\s*(.+?)\\s*$",
            RegexOption.IGNORE_CASE,
        )
        val EXPORT_SYMBOL = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        val TABLE_SEPARATOR = Regex(":?-{3,}:?")
        val PACKAGE_NAME = Regex("[a-z0-9-]+")
        val PACKAGE_SEPARATOR = Regex("\\s*(?:/|,)\\s*")
        val LOWER_TO_UPPER = Regex("([a-z0-9])([A-Z])")
        val ACRONYM_BOUNDARY = Regex("([A-Z])([A-Z][a-z])")
        val SLUG_SEPARATOR = Regex("[^a-z0-9-]+")
        val WHITESPACE = Regex("\\s+")
    }
}
