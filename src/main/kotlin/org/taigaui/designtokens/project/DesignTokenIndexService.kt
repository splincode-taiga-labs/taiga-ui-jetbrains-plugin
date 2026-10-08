package org.taigaui.designtokens.project

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import org.taigaui.designtokens.diagnostics.PerformanceDiagnostics
import org.taigaui.designtokens.diagnostics.PerformanceMetric
import org.taigaui.designtokens.index.DesignTokenDeclaration
import org.taigaui.designtokens.index.DesignTokenIndex
import org.taigaui.designtokens.index.DesignTokensPackageScanner
import org.taigaui.designtokens.packageinfo.DesignTokensPackage
import org.taigaui.designtokens.packageinfo.DesignTokensPackageResolver
import org.taigaui.designtokens.psi.PsiDesignTokenSourceExtractor
import org.taigaui.designtokens.resolution.DesignTokenResolutionGroup
import org.taigaui.designtokens.resolution.DesignTokenValueResolver
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class DesignTokenIndexService(
    project: Project,
) {
    private val packageResolver = DesignTokensPackageResolver()
    private val sourceExtractor = PsiDesignTokenSourceExtractor(project)
    private val packageScanner =
        DesignTokensPackageScanner(
            sourceExtractor = sourceExtractor,
        )
    private val cache =
        DesignTokenIndexCache { designTokensPackage ->
            val declarations =
                PerformanceDiagnostics.measure(PerformanceMetric.PACKAGE_SCAN) {
                    packageScanner.scan(designTokensPackage)
                }

            DesignTokenIndex.build(
                packageRoot = designTokensPackage.realRoot,
                declarations = declarations,
            )
        }
    private val packageNameCatalogCache =
        DesignTokenIndexCache { designTokensPackage ->
            val declarations =
                PerformanceDiagnostics.measure(PerformanceMetric.PACKAGE_SCAN) {
                    packageScanner.scanAll(designTokensPackage)
                }

            DesignTokenIndex.build(
                packageRoot = designTokensPackage.realRoot,
                declarations = declarations,
            )
        }
    private val projectStylesheetIndexProvider =
        ProjectStylesheetIndexProvider(
            project = project,
            packageResolver = packageResolver,
            sourceExtractor = sourceExtractor,
        )
    private val resolutionSnapshotCache = DesignTokenResolutionSnapshotCache()

    internal val cachedPackageCount: Int
        get() = cache.size

    internal val cachedProjectIndexCount: Int
        get() = projectStylesheetIndexProvider.size

    internal val cachedResolutionSnapshotCount: Int
        get() = resolutionSnapshotCache.size

    init {
        project.messageBus
            .connect()
            .subscribe(
                VirtualFileManager.VFS_CHANGES,
                object : BulkFileListener {
                    override fun after(events: List<VFileEvent>) {
                        invalidate(events.flatMap(VfsEventPaths::from))
                    }
                },
            )

        EditorFactory
            .getInstance()
            .eventMulticaster
            .addDocumentListener(
                object : DocumentListener {
                    override fun documentChanged(event: DocumentEvent) {
                        FileDocumentManager
                            .getInstance()
                            .getFile(event.document)
                            ?.path
                            ?.toPathOrNull()
                            ?.let { path -> invalidate(listOf(path)) }
                    }
                },
                project,
            )
    }

    fun getIndex(sourceFile: Path): DesignTokenIndex? =
        runCatching {
            packageResolver
                .resolve(sourceFile)
                ?.let(cache::getOrBuild)
        }.onFailure { error ->
            LOG.warn(
                "Failed to build the installed Taiga UI style index for $sourceFile",
                error,
            )
        }.getOrNull()

    fun resolveToken(
        sourceFile: Path,
        tokenName: String,
        localOverrides: List<DesignTokenDeclaration> = emptyList(),
    ): List<DesignTokenResolutionGroup> {
        val snapshot = resolutionSnapshot(sourceFile)
        val resolver =
            if (localOverrides.isEmpty()) {
                snapshot.resolver
            } else {
                val localIndex =
                    DesignTokenIndex.build(
                        packageRoot = sourceFile.parent ?: sourceFile,
                        declarations = localOverrides,
                    )
                val indexes = listOfNotNull(snapshot.mergedIndex, localIndex)

                DesignTokenValueResolver(DesignTokenIndex.merge(indexes))
            }

        return resolver
            ?.let { valueResolver ->
                PerformanceDiagnostics.measure(PerformanceMetric.VALUE_RESOLUTION) {
                    valueResolver.resolveGrouped(tokenName)
                }
            }.orEmpty()
    }

    internal fun completionTokenNames(sourceFile: Path): List<String> =
        resolutionSnapshot(
            sourceFile = sourceFile,
            requireCompleteNameCatalog = true,
        ).tokenNames

    internal fun completionTokenCatalog(sourceFile: Path): List<DesignTokenCatalogEntry> =
        resolutionSnapshot(
            sourceFile = sourceFile,
            requireCompleteNameCatalog = true,
        ).tokenCatalog

    internal fun contextKey(sourceFile: Path): TokenContextKey {
        val normalizedSourceFile = sourceFile.toAbsolutePath().normalize()

        return contextKeyOrFallback(normalizedSourceFile) {
            val designTokensPackage = packageResolver.resolve(normalizedSourceFile)
            val projectRequest =
                projectStylesheetIndexProvider.request(normalizedSourceFile, designTokensPackage)

            tokenContextKey(designTokensPackage, projectRequest)
        }
    }

    private fun contextKeyOrFallback(
        normalizedSourceFile: Path,
        operation: () -> TokenContextKey,
    ): TokenContextKey =
        runCatching(operation).getOrElse {
            TokenContextKey(
                workspaceRoot = normalizedSourceFile.parent,
                projectRoot = normalizedSourceFile.parent,
                packageRoot = null,
                projectEntryFiles = listOf(normalizedSourceFile),
            ).normalized()
        }

    internal fun isIndexCached(sourceFile: Path): Boolean =
        runCatching {
            val normalizedSourceFile = sourceFile.toAbsolutePath().normalize()
            val designTokensPackage = packageResolver.resolve(normalizedSourceFile)
            val projectRequest =
                projectStylesheetIndexProvider.request(normalizedSourceFile, designTokensPackage)
            val packageIndexCached = designTokensPackage?.let(cache::contains) ?: true
            val packageNameCatalogCached =
                designTokensPackage
                    ?.takeIf { packageSet -> packageSet.needsCompleteNameCatalog() }
                    ?.let(packageNameCatalogCache::contains)
                    ?: true
            val projectIndexCached = projectStylesheetIndexProvider.isCached(projectRequest)

            packageIndexCached && packageNameCatalogCached && projectIndexCached
        }.getOrDefault(false)

    internal fun invalidate(changedPaths: Collection<Path>): Int {
        val packageInvalidated = cache.invalidate(changedPaths)

        packageNameCatalogCache.invalidate(changedPaths)

        return packageInvalidated + projectStylesheetIndexProvider.invalidate(changedPaths)
    }

    internal fun clear() {
        cache.clear()
        packageNameCatalogCache.clear()
        projectStylesheetIndexProvider.clear()
        resolutionSnapshotCache.clear()
    }

    internal fun resolutionSnapshot(
        sourceFile: Path,
        requireCompleteNameCatalog: Boolean = false,
    ): DesignTokenResolutionSnapshot {
        val normalizedSourceFile = sourceFile.toAbsolutePath().normalize()
        val designTokensPackage = packageResolver.resolve(normalizedSourceFile)
        val projectRequest =
            projectStylesheetIndexProvider.request(normalizedSourceFile, designTokensPackage)
        val installedIndex = getIndex(normalizedSourceFile)
        val nameCatalogIndex =
            if (requireCompleteNameCatalog) {
                designTokensPackage
                    ?.takeIf { packageSet -> packageSet.needsCompleteNameCatalog() }
                    ?.let(packageNameCatalogCache::getOrBuild)
            } else {
                null
            }
        val projectIndex = projectStylesheetIndexProvider.getIndex(projectRequest)

        return resolutionSnapshotCache.getOrBuild(
            contextKey = tokenContextKey(designTokensPackage, projectRequest),
            inputs =
                DesignTokenResolutionSnapshotInputs(
                    installedIndex = installedIndex,
                    projectIndex = projectIndex,
                    nameCatalogIndex = nameCatalogIndex,
                ),
        )
    }

    private companion object {
        val LOG = Logger.getInstance(DesignTokenIndexService::class.java)
    }
}

internal object VfsEventPaths {
    fun from(event: VFileEvent): Set<Path> =
        buildSet {
            event.path.toPathOrNull()?.let(::add)

            when (event) {
                is VFileMoveEvent -> {
                    Path.of(event.oldParent.path, event.file.name).let(::add)
                    Path.of(event.newParent.path, event.file.name).let(::add)
                }

                is VFilePropertyChangeEvent -> addRenamePaths(event)
            }
        }

    private fun MutableSet<Path>.addRenamePaths(event: VFilePropertyChangeEvent) {
        if (event.propertyName == VirtualFile.PROP_NAME) {
            val parentPath = event.file.parent?.path
            val oldName = event.oldValue as? String
            val newName = event.newValue as? String

            if (parentPath != null && oldName != null && newName != null) {
                add(Path.of(parentPath, oldName))
                add(Path.of(parentPath, newName))
            }
        }
    }
}

private fun DesignTokensPackage.needsCompleteNameCatalog(): Boolean =
    sourcePackages.any { sourcePackage -> sourcePackage.name == PROPRIETARY_PACKAGE }

private fun tokenContextKey(
    designTokensPackage: DesignTokensPackage?,
    projectRequest: ProjectStylesheetIndexRequest?,
): TokenContextKey =
    TokenContextKey(
        workspaceRoot = projectRequest?.workspaceRoot,
        projectRoot = projectRequest?.projectRoot,
        packageRoot = designTokensPackage?.realRoot,
        projectEntryFiles = projectRequest?.entryFiles.orEmpty(),
    ).normalized()

private fun String.toPathOrNull(): Path? = runCatching { Path.of(this) }.getOrNull()

private const val PROPRIETARY_PACKAGE = "@taiga-ui/proprietary"
