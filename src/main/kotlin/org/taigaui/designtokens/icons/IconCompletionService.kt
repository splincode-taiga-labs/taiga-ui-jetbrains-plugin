package org.taigaui.designtokens.icons

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.taigaui.designtokens.cache.CoalescingRefreshCallbacks
import org.taigaui.designtokens.cache.RefreshCallback
import org.taigaui.designtokens.diagnostics.PerformanceDiagnostics
import org.taigaui.designtokens.diagnostics.PerformanceMetric
import java.nio.file.Path

@Service(Service.Level.PROJECT)
internal class IconCompletionService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    private val loader = IconCatalogLoader()
    private val cache = IconCatalogCache()
    private val lock = Any()
    private val pendingWarmups = mutableMapOf<Path, PendingWarmup>()

    init {
        project.messageBus
            .connect()
            .subscribe(
                VirtualFileManager.VFS_CHANGES,
                object : BulkFileListener {
                    override fun after(events: List<VFileEvent>) {
                        invalidate(events.flatMap(IconVfsEventPaths::from))
                    }
                },
            )
    }

    fun catalogFor(
        sourceFile: Path,
        onUpdated: RefreshCallback<*>,
    ): IconCatalog? {
        val scopeRoot =
            loader
                .resolveScopeRoot(sourceFile)
                ?.toAbsolutePath()
                ?.normalize()
                ?: return IconCatalog(emptyList())
        val lookup = cache.lookup(scopeRoot)

        if (lookup == null || !lookup.isFresh) {
            scheduleWarmup(scopeRoot, onUpdated)
        }

        return lookup?.catalog
    }

    fun svgSourceFor(
        sourceFile: Path,
        iconName: String,
    ): IconSvgSource? {
        val scopeRoot =
            loader
                .resolveScopeRoot(sourceFile)
                ?.toAbsolutePath()
                ?.normalize()
                ?: return null
        val lookup = cache.lookup(scopeRoot)

        if (lookup == null || !lookup.isFresh) {
            scheduleWarmup(scopeRoot)
        }

        return lookup?.catalog?.svgSource(iconName)
    }

    internal fun loadNow(sourceFile: Path): List<String> =
        loader
            .resolveScopeRoot(sourceFile)
            ?.toAbsolutePath()
            ?.normalize()
            ?.let(::loadScopeNow)
            .orEmpty()

    internal fun invalidate(changedPaths: Collection<Path>): Int {
        val normalizedPaths =
            changedPaths
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .distinct()
        val affectedScopes =
            synchronized(lock) {
                (cache.scopeRoots + pendingWarmups.keys)
                    .filter { scopeRoot ->
                        normalizedPaths.any { changedPath ->
                            loader.isAffected(scopeRoot, changedPath)
                        }
                    }.toSet()
            }

        affectedScopes.forEach(cache::invalidate)

        return affectedScopes.size
    }

    internal fun clear() {
        synchronized(lock) {
            pendingWarmups.clear()
            cache.clear()
            loader.clearResolvedContexts()
        }
    }

    private fun loadScopeNow(scopeRoot: Path): List<String> {
        val lookup = cache.lookup(scopeRoot)

        if (lookup?.isFresh == true) {
            return lookup.catalog.names
        }

        val generation = cache.generation(scopeRoot)
        val result = loadCatalog(scopeRoot)

        cache.publish(scopeRoot, generation, result)

        return result.catalog.names
    }

    private fun scheduleWarmup(
        scopeRoot: Path,
        callback: RefreshCallback<*>? = null,
    ) {
        val access =
            synchronized(lock) {
                pendingWarmups[scopeRoot]
                    ?.also { current -> callback?.let(current.callbacks::add) }
                    ?.let { current -> WarmupAccess(current, shouldStart = false) }
                    ?: PendingWarmup(
                        generation = cache.generation(scopeRoot),
                        callbacks =
                            CoalescingRefreshCallbacks().also { callbacks ->
                                callback?.let(callbacks::add)
                            },
                    ).also { created -> pendingWarmups[scopeRoot] = created }
                        .let { created -> WarmupAccess(created, shouldStart = true) }
            }

        if (access.shouldStart) {
            launchWarmup(scopeRoot, access.pending)
        }
    }

    private fun launchWarmup(
        scopeRoot: Path,
        pending: PendingWarmup,
    ) {
        coroutineScope.launch(Dispatchers.IO + CoroutineName("Taiga UI icon completion warmup")) {
            val result =
                runCatching { loadCatalog(scopeRoot) }
                    .getOrElse {
                        synchronized(lock) {
                            if (pendingWarmups[scopeRoot] === pending) {
                                pendingWarmups.remove(scopeRoot)
                            }
                        }
                        return@launch
                    }
            val outcome =
                synchronized(lock) {
                    if (pendingWarmups[scopeRoot] !== pending) {
                        WarmupOutcome.Ignore
                    } else if (cache.publish(scopeRoot, pending.generation, result)) {
                        pendingWarmups.remove(scopeRoot)
                        WarmupOutcome.Publish(pending.callbacks.take())
                    } else {
                        val retry =
                            PendingWarmup(
                                generation = cache.generation(scopeRoot),
                                callbacks = pending.callbacks,
                            )

                        pendingWarmups[scopeRoot] = retry
                        WarmupOutcome.Retry(retry)
                    }
                }

            when (outcome) {
                WarmupOutcome.Ignore -> Unit
                is WarmupOutcome.Retry -> launchWarmup(scopeRoot, outcome.pending)
                is WarmupOutcome.Publish ->
                    if (outcome.callbacks.isNotEmpty() && !project.isDisposed) {
                        withContext(Dispatchers.EDT) {
                            outcome.callbacks.forEach { callback -> callback.invokeIfActive() }
                        }
                    }
            }
        }
    }

    private fun loadCatalog(scopeRoot: Path): IconCatalogLoadResult =
        PerformanceDiagnostics.measure(PerformanceMetric.ICON_CATALOG_LOAD) {
            loader.loadCatalogWithPolicy(scopeRoot)
        }

    private data class PendingWarmup(
        val generation: Long,
        val callbacks: CoalescingRefreshCallbacks,
    )

    private data class WarmupAccess(
        val pending: PendingWarmup,
        val shouldStart: Boolean,
    )

    private sealed interface WarmupOutcome {
        data object Ignore : WarmupOutcome

        data class Retry(
            val pending: PendingWarmup,
        ) : WarmupOutcome

        data class Publish(
            val callbacks: List<RefreshCallback<*>>,
        ) : WarmupOutcome
    }
}

internal fun IconCompletionService.namesFor(
    sourceFile: Path,
    onUpdated: RefreshCallback<*>,
): List<String>? = catalogFor(sourceFile, onUpdated)?.names

internal object IconVfsEventPaths {
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

private fun String.toPathOrNull(): Path? = runCatching { Path.of(this) }.getOrNull()
