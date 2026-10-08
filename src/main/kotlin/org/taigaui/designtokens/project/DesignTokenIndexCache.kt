package org.taigaui.designtokens.project

import org.taigaui.designtokens.index.DesignTokenIndex
import org.taigaui.designtokens.packageinfo.DesignTokenSourcePackage
import org.taigaui.designtokens.packageinfo.DesignTokensPackage
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

internal fun interface DesignTokenIndexBuilder {
    fun build(designTokensPackage: DesignTokensPackage): DesignTokenIndex
}

internal data class DesignTokensPackageIdentity(
    val stableIdentity: String,
    val version: String,
) {
    companion object {
        fun from(designTokensPackage: DesignTokensPackage): DesignTokensPackageIdentity =
            DesignTokensPackageIdentity(
                stableIdentity = designTokensPackage.cacheIdentity,
                version = designTokensPackage.cacheVersion,
            )
    }
}

internal class DesignTokenIndexCache(
    private val indexBuilder: DesignTokenIndexBuilder,
) {
    private val lock = Any()
    private val entries = linkedMapOf<DesignTokensPackageIdentity, CacheEntry>()
    private val pendingBuilds = linkedMapOf<DesignTokensPackageIdentity, PendingBuild>()

    val size: Int
        get() = synchronized(lock) { entries.size }

    fun contains(designTokensPackage: DesignTokensPackage): Boolean {
        val normalizedPackage = normalizePackage(designTokensPackage)
        val identity = DesignTokensPackageIdentity.from(normalizedPackage)

        return synchronized(lock) {
            entries.containsKey(identity)
        }
    }

    fun getOrBuild(designTokensPackage: DesignTokensPackage): DesignTokenIndex {
        val normalizedPackage = normalizePackage(designTokensPackage)
        val identity = DesignTokensPackageIdentity.from(normalizedPackage)
        val access =
            synchronized(lock) {
                removeReplacedPackages(
                    identity = identity,
                    logicalRoot = normalizedPackage.root,
                )

                entries[identity]?.let { entry ->
                    entry.addPackage(normalizedPackage)

                    return entry.index
                }

                pendingBuilds[identity]
                    ?.also { pendingBuild -> pendingBuild.addPackage(normalizedPackage) }
                    ?.let { pendingBuild -> BuildAccess(pendingBuild, shouldBuild = false) }
                    ?: PendingBuild(normalizedPackage)
                        .also { pendingBuild -> pendingBuilds[identity] = pendingBuild }
                        .let { pendingBuild -> BuildAccess(pendingBuild, shouldBuild = true) }
            }

        return if (access.shouldBuild) {
            buildAndPublish(identity, normalizedPackage, access.pendingBuild)
        } else {
            access.pendingBuild.await()
        }
    }

    fun invalidate(changedPaths: Collection<Path>): Int =
        synchronized(lock) {
            val normalizedPaths =
                changedPaths
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .distinct()
            val sizeBefore = entries.size

            entries.entries.removeIf { (_, entry) ->
                normalizedPaths.any(entry::isAffectedBy)
            }
            pendingBuilds.entries.removeIf { (_, pendingBuild) ->
                normalizedPaths.any(pendingBuild::isAffectedBy)
            }

            sizeBefore - entries.size
        }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            pendingBuilds.clear()
        }
    }

    private fun buildAndPublish(
        identity: DesignTokensPackageIdentity,
        designTokensPackage: DesignTokensPackage,
        pendingBuild: PendingBuild,
    ): DesignTokenIndex =
        runCatching { indexBuilder.build(designTokensPackage) }
            .fold(
                onSuccess = { index -> publishSuccess(identity, pendingBuild, index) },
                onFailure = { error -> throw recordFailure(identity, pendingBuild, error) },
            )

    private fun publishSuccess(
        identity: DesignTokensPackageIdentity,
        pendingBuild: PendingBuild,
        index: DesignTokenIndex,
    ): DesignTokenIndex {
        synchronized(lock) {
            if (pendingBuilds[identity] === pendingBuild) {
                pendingBuilds.remove(identity)
                entries[identity] = pendingBuild.toCacheEntry(index)
            }
        }
        pendingBuild.complete(index)

        return index
    }

    private fun recordFailure(
        identity: DesignTokensPackageIdentity,
        pendingBuild: PendingBuild,
        error: Throwable,
    ): Throwable {
        synchronized(lock) {
            if (pendingBuilds[identity] === pendingBuild) {
                pendingBuilds.remove(identity)
            }
        }
        pendingBuild.completeExceptionally(error)

        return error
    }

    private fun removeReplacedPackages(
        identity: DesignTokensPackageIdentity,
        logicalRoot: Path,
    ) {
        entries.entries.removeIf { (cachedIdentity, entry) ->
            cachedIdentity.isReplacedBy(identity, logicalRoot, entry.logicalRoots)
        }
        pendingBuilds.entries.removeIf { (cachedIdentity, pendingBuild) ->
            cachedIdentity.isReplacedBy(identity, logicalRoot, pendingBuild.logicalRoots)
        }
    }

    private fun DesignTokensPackageIdentity.isReplacedBy(
        identity: DesignTokensPackageIdentity,
        logicalRoot: Path,
        logicalRoots: Set<Path>,
    ): Boolean {
        val replacedVersion = stableIdentity == identity.stableIdentity && version != identity.version
        val replacedTarget = logicalRoot in logicalRoots && this != identity

        return replacedVersion || replacedTarget
    }

    private data class CacheEntry(
        val index: DesignTokenIndex,
        val logicalRoots: MutableSet<Path>,
        val packageRoots: MutableSet<Path>,
    ) {
        fun addPackage(designTokensPackage: DesignTokensPackage) {
            logicalRoots.add(designTokensPackage.root)
            packageRoots.addAll(designTokensPackage.cacheRoots())
        }

        fun isAffectedBy(changedPath: Path): Boolean =
            rootsAreAffected(
                logicalRoots = logicalRoots,
                packageRoots = packageRoots,
                changedPath = changedPath,
            )
    }

    private class PendingBuild(
        designTokensPackage: DesignTokensPackage,
    ) {
        private val future = CompletableFuture<DesignTokenIndex>()
        val logicalRoots = linkedSetOf<Path>()
        private val packageRoots = linkedSetOf<Path>()

        init {
            addPackage(designTokensPackage)
        }

        fun addPackage(designTokensPackage: DesignTokensPackage) {
            logicalRoots.add(designTokensPackage.root)
            packageRoots.addAll(designTokensPackage.cacheRoots())
        }

        fun isAffectedBy(changedPath: Path): Boolean =
            rootsAreAffected(
                logicalRoots = logicalRoots,
                packageRoots = packageRoots,
                changedPath = changedPath,
            )

        fun toCacheEntry(index: DesignTokenIndex): CacheEntry =
            CacheEntry(
                index = index,
                logicalRoots = logicalRoots.toMutableSet(),
                packageRoots = packageRoots.toMutableSet(),
            )

        fun complete(index: DesignTokenIndex) {
            future.complete(index)
        }

        fun completeExceptionally(error: Throwable) {
            future.completeExceptionally(error)
        }

        fun await(): DesignTokenIndex =
            try {
                future.join()
            } catch (error: CompletionException) {
                throw error.cause ?: error
            }
    }

    private data class BuildAccess(
        val pendingBuild: PendingBuild,
        val shouldBuild: Boolean,
    )

    private companion object {
        val STYLESHEET_EXTENSIONS = setOf("css", "less", "scss")

        fun normalizePackage(designTokensPackage: DesignTokensPackage): DesignTokensPackage =
            designTokensPackage.copy(
                root = designTokensPackage.root.toAbsolutePath().normalize(),
                realRoot = designTokensPackage.realRoot.toAbsolutePath().normalize(),
                discoveryRoot = designTokensPackage.discoveryRoot.toAbsolutePath().normalize(),
                invalidationRoots =
                    designTokensPackage.invalidationRoots
                        .map(Path::toAbsolutePath)
                        .map(Path::normalize)
                        .toSet(),
                workspaceRoot = designTokensPackage.workspaceRoot?.toAbsolutePath()?.normalize(),
                sourcePackages = designTokensPackage.sourcePackages.map(::normalizeSourcePackage),
            )

        fun normalizeSourcePackage(sourcePackage: DesignTokenSourcePackage): DesignTokenSourcePackage =
            sourcePackage.copy(
                root = sourcePackage.root.toAbsolutePath().normalize(),
                realRoot = sourcePackage.realRoot.toAbsolutePath().normalize(),
                sourceRoots =
                    sourcePackage.sourceRoots
                        .map(Path::toAbsolutePath)
                        .map(Path::normalize),
            )

        fun DesignTokensPackage.cacheRoots(): Set<Path> =
            buildSet {
                add(root)
                add(realRoot)
                add(discoveryRoot)
                addAll(invalidationRoots)
                effectiveSourcePackages.forEach { sourcePackage ->
                    add(sourcePackage.root)
                    add(sourcePackage.realRoot)
                    addAll(sourcePackage.sourceRoots)
                }
            }

        fun rootsAreAffected(
            logicalRoots: Set<Path>,
            packageRoots: Set<Path>,
            changedPath: Path,
        ): Boolean =
            (logicalRoots + packageRoots).any { packageRoot ->
                val packageRootChanged = changedPath == packageRoot
                val packageAncestorChanged = packageRoot.startsWith(changedPath)
                val relevantPackageFileChanged =
                    changedPath.startsWith(packageRoot) &&
                        changedPath.isRelevantPackagePath()

                packageRootChanged || packageAncestorChanged || relevantPackageFileChanged
            }

        fun Path.isRelevantPackagePath(): Boolean {
            val fileName = fileName?.toString()?.lowercase() ?: return true
            val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")

            return fileName == "package.json" ||
                extension in STYLESHEET_EXTENSIONS ||
                '.' !in fileName
        }
    }
}
