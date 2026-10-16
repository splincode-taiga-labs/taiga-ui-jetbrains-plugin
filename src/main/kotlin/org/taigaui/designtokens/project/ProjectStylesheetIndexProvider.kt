package org.taigaui.designtokens.project

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import org.taigaui.designtokens.diagnostics.PerformanceDiagnostics
import org.taigaui.designtokens.diagnostics.PerformanceMetric
import org.taigaui.designtokens.index.DesignTokenIndex
import org.taigaui.designtokens.index.DesignTokenSourceExtractor
import org.taigaui.designtokens.index.PROJECT_STYLES_PACKAGE
import org.taigaui.designtokens.packageinfo.DesignTokensPackage
import org.taigaui.designtokens.packageinfo.DesignTokensPackageResolver
import java.nio.file.Files
import java.nio.file.Path

internal class ProjectStylesheetIndexProvider(
    private val project: Project,
    private val packageResolver: DesignTokensPackageResolver,
    sourceExtractor: DesignTokenSourceExtractor,
) {
    private val importCache =
        ProjectStylesheetImportCache(
            readText = ::readProjectText,
            modificationStampProvider =
                ProjectStylesheetModificationStampProvider { sourceFile ->
                    modificationStamp(sourceFile)
                },
        )
    private val graph =
        DesignTokenProjectStylesheetGraph(
            project = project,
            readText = ::readProjectText,
            imports = importCache::imports,
        )
    private val declarationCache =
        ProjectStylesheetDeclarationCache(sourceExtractor) { sourceFile ->
            modificationStamp(sourceFile)
        }
    private val cache =
        ProjectStylesheetIndexCache { request ->
            buildIndex(request)
        }

    val size: Int
        get() = cache.size

    fun isCached(request: ProjectStylesheetIndexRequest?): Boolean =
        request
            ?.let(cache::contains)
            ?: true

    fun getIndex(request: ProjectStylesheetIndexRequest?): DesignTokenIndex? =
        request
            ?.let(cache::getOrBuild)

    fun invalidate(changedPaths: Collection<Path>): Int {
        declarationCache.invalidate(changedPaths)
        importCache.invalidate(changedPaths)

        return cache.invalidate(changedPaths)
    }

    fun clear() {
        cache.clear()
        declarationCache.clear()
        importCache.clear()
    }

    fun request(
        sourceFile: Path,
        designTokensPackage: DesignTokensPackage? = packageResolver.resolve(sourceFile),
    ): ProjectStylesheetIndexRequest? {
        val normalizedSourceFile = sourceFile.toAbsolutePath().normalize()

        if (isInstalledTaigaUiSource(normalizedSourceFile, designTokensPackage)) {
            return null
        }

        val workspaceRootHint =
            designTokensPackage
                ?.workspaceRoot
                ?: designTokensPackage
                    ?.discoveryRoot
                    ?.parent
                    ?.parent

        return graph.createRequest(
            sourceFile = normalizedSourceFile,
            workspaceRootHint = workspaceRootHint,
        )
    }

    private fun buildIndex(request: ProjectStylesheetIndexRequest): ProjectStylesheetIndexBuildResult {
        val scope =
            PerformanceDiagnostics.measure(PerformanceMetric.PROJECT_GRAPH_BUILD) {
                graph.buildScope(request)
            }
        val declarations =
            scope.sourceFiles
                .flatMap { sourceFile ->
                    declarationCache
                        .extract(sourceFile)
                        .sortedBy { declaration -> declaration.line }
                }.mapIndexed { cascadeOrder, declaration ->
                    declaration.copy(
                        packageName = PROJECT_STYLES_PACKAGE,
                        packageRoot = scope.projectRoot,
                        cascadeOrder = cascadeOrder,
                    )
                }
        val index =
            DesignTokenIndex.build(
                packageRoot = scope.projectRoot,
                declarations = declarations,
            )

        return ProjectStylesheetIndexBuildResult(
            index = index,
            dependencies = scope.sourceFiles.toSet(),
        )
    }

    private fun modificationStamp(path: Path): Long? {
        val normalizedPath = path.toAbsolutePath().normalize()
        val virtualFile = findVirtualFile(normalizedPath)

        return virtualFile
            ?.let { file ->
                FileDocumentManager
                    .getInstance()
                    .getCachedDocument(file)
                    ?.modificationStamp
                    ?: file.modificationStamp
            } ?: runCatching { Files.getLastModifiedTime(normalizedPath).toMillis() }.getOrNull()
    }

    private fun readProjectText(path: Path): String? {
        val normalizedPath = path.toAbsolutePath().normalize()
        val documentText =
            findVirtualFile(normalizedPath)
                ?.let { file -> FileDocumentManager.getInstance().getCachedDocument(file) }
                ?.text

        return documentText ?: runCatching { Files.readString(normalizedPath) }.getOrNull()
    }

    private fun findVirtualFile(path: Path) =
        LocalFileSystem
            .getInstance()
            .let { fileSystem ->
                fileSystem.findFileByNioFile(path)
                    ?: if (project.isInitialized) {
                        fileSystem.refreshAndFindFileByNioFile(path)
                    } else {
                        null
                    }
            }

    private fun isInstalledTaigaUiSource(
        sourceFile: Path,
        designTokensPackage: DesignTokensPackage?,
    ): Boolean =
        designTokensPackage
            ?.effectiveSourcePackages
            ?.any { sourcePackage ->
                buildList {
                    add(sourcePackage.root)
                    add(sourcePackage.realRoot)
                    addAll(sourcePackage.sourceRoots)
                }.map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .any(sourceFile::startsWith)
            } == true
}
