package org.taigaui.designtokens.index

import org.taigaui.designtokens.packageinfo.DesignTokenSourcePackage
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque

internal class DesignTokenPackageImportGraph {
    fun findReachableFiles(
        entryFiles: Collection<Path>,
        sourcePackages: List<DesignTokenSourcePackage>,
    ): Map<String, List<Path>> {
        val packagesByName = sourcePackages.associateBy(DesignTokenSourcePackage::name)
        val packagesByRoot =
            sourcePackages.sortedByDescending { sourcePackage ->
                sourcePackage.realRoot
                    .toAbsolutePath()
                    .normalize()
                    .nameCount
            }
        val queue = ArrayDeque(entryFiles.map { path -> path.normalized() })
        val visited = linkedSetOf<Path>()
        val filesByPackage = linkedMapOf<String, MutableSet<Path>>()

        while (queue.isNotEmpty()) {
            val sourceFile = queue.removeFirst()

            if (visited.add(sourceFile)) {
                sourceFile.sourcePackage(packagesByRoot)?.let { sourcePackage ->
                    filesByPackage
                        .getOrPut(sourcePackage.name, ::linkedSetOf)
                        .add(sourceFile)

                    sourceFile
                        .imports()
                        .mapNotNull { importPath ->
                            resolveImport(
                                sourceFile = sourceFile,
                                importPath = importPath,
                                packagesByName = packagesByName,
                            )
                        }.forEach(queue::addLast)
                }
            }
        }

        return filesByPackage.mapValues { (_, files) -> files.sortedBy(Path::toString) }
    }

    private fun resolveImport(
        sourceFile: Path,
        importPath: String,
        packagesByName: Map<String, DesignTokenSourcePackage>,
    ): Path? {
        val normalizedImport =
            importPath
                .substringBefore('?')
                .substringBefore('#')
                .removePrefix("~")
                .trim()
        val importedPath =
            normalizedImport
                .takeUnless { value -> value.isEmpty() || value.isExternalImport() }
                ?.let { value ->
                    if (value.startsWith(TAIGA_UI_PACKAGE_PREFIX)) {
                        resolvePackageImport(value, packagesByName)
                    } else {
                        sourceFile.parent?.resolve(value)
                    }
                }

        return importedPath?.resolveSourceFile()
    }

    private fun resolvePackageImport(
        importPath: String,
        packagesByName: Map<String, DesignTokenSourcePackage>,
    ): Path? =
        importPath
            .split('/')
            .takeIf { segments -> segments.size >= PACKAGE_PATH_SEGMENTS }
            ?.let { segments ->
                val packageName = segments.take(PACKAGE_NAME_SEGMENTS).joinToString("/")
                val relativePath = segments.drop(PACKAGE_NAME_SEGMENTS).joinToString("/")

                packagesByName[packageName]
                    ?.realRoot
                    ?.resolve(relativePath)
            }

    private fun Path.resolveSourceFile(): Path? {
        val path = normalized()
        val candidates =
            buildList {
                add(path)

                if (path.extension().isEmpty()) {
                    SUPPORTED_EXTENSIONS.forEach { extension ->
                        add(path.resolveSibling("${path.fileName}.$extension"))
                    }
                }

                if (Files.isDirectory(path)) {
                    SUPPORTED_EXTENSIONS.forEach { extension ->
                        add(path.resolve("index.$extension"))
                    }
                }
            }

        return candidates.firstOrNull { candidate ->
            Files.isRegularFile(candidate) && candidate.extension() in SUPPORTED_EXTENSIONS
        }
    }

    private fun Path.imports(): List<String> =
        runCatching { Files.readString(this) }
            .getOrNull()
            ?.let { content ->
                IMPORT_PATTERN
                    .findAll(content)
                    .map { match -> match.groupValues[1].trim() }
                    .filter(String::isNotEmpty)
                    .toList()
            }.orEmpty()

    private fun Path.sourcePackage(sourcePackages: List<DesignTokenSourcePackage>): DesignTokenSourcePackage? =
        sourcePackages.firstOrNull { sourcePackage ->
            startsWith(sourcePackage.realRoot.normalized())
        }

    private fun String.isExternalImport(): Boolean =
        startsWith("http://", ignoreCase = true) ||
            startsWith("https://", ignoreCase = true) ||
            startsWith("data:", ignoreCase = true)

    private fun Path.normalized(): Path = toAbsolutePath().normalize()

    private fun Path.extension(): String =
        fileName
            ?.toString()
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            .orEmpty()

    private companion object {
        const val PACKAGE_NAME_SEGMENTS = 2
        const val PACKAGE_PATH_SEGMENTS = 3
        const val TAIGA_UI_PACKAGE_PREFIX = "@taiga-ui/"
        val SUPPORTED_EXTENSIONS = listOf("less", "css", "scss")
        val IMPORT_PATTERN = Regex(
            pattern = """@import\s*(?:\([^)]*\)\s*)?(?:url\(\s*)?['\"]([^'\"]+)['\"]\s*\)?""",
            option = RegexOption.IGNORE_CASE,
        )
    }
}
