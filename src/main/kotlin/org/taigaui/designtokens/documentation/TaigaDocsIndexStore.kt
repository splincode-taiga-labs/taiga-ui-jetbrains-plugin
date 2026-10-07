package org.taigaui.designtokens.documentation

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

internal class TaigaDocsIndexStore(
    private val coroutineScope: CoroutineScope,
    private val repository: TaigaDocsRepository = TaigaDocsRepository(),
) {
    private val lock = Any()
    private val snapshots = mutableMapOf<TaigaDocsSource, TaigaDocsIndex>()
    private val generations = mutableMapOf<TaigaDocsSource, Long>()
    private val pendingLoads = mutableMapOf<TaigaDocsSource, Deferred<TaigaDocsIndex?>>()
    private val pendingRefreshes = mutableMapOf<TaigaDocsSource, Deferred<TaigaDocsIndex?>>()

    fun cached(source: TaigaDocsSource): TaigaDocsIndex? = synchronized(lock) { snapshots[source] }

    suspend fun indexFor(source: TaigaDocsSource): TaigaDocsIndex? {
        cached(source)?.let { return it }

        return initialLoad(source).await() ?: cached(source)
    }

    suspend fun refresh(source: TaigaDocsSource): TaigaDocsIndex? =
        refreshLoad(source, allowCachedFallback = true).await() ?: cached(source)

    fun invalidate(source: TaigaDocsSource) {
        invalidate(source, removeDiskCache = false)
    }

    fun invalidate(
        source: TaigaDocsSource,
        removeDiskCache: Boolean,
    ) {
        val jobs =
            synchronized(lock) {
                generations[source] = (generations[source] ?: 0L) + 1L
                snapshots.remove(source)

                listOfNotNull(
                    pendingLoads.remove(source),
                    pendingRefreshes.remove(source),
                )
            }

        jobs.forEach { job -> job.cancel() }

        if (removeDiskCache) {
            repository.invalidate(source)
        }
    }

    fun clearMemory() {
        val jobs =
            synchronized(lock) {
                val currentJobs = (pendingLoads.values + pendingRefreshes.values).distinct()

                snapshots.clear()
                generations.clear()
                pendingLoads.clear()
                pendingRefreshes.clear()
                currentJobs
            }

        jobs.forEach { job -> job.cancel() }
    }

    private fun initialLoad(source: TaigaDocsSource): Deferred<TaigaDocsIndex?> =
        synchronized(lock) {
            pendingLoads[source] ?: createInitialLoad(source).also { created ->
                pendingLoads[source] = created
                created.invokeOnCompletion {
                    synchronized(lock) {
                        if (pendingLoads[source] === created) {
                            pendingLoads.remove(source)
                        }
                    }
                }
            }
        }

    private fun createInitialLoad(source: TaigaDocsSource): Deferred<TaigaDocsIndex?> {
        val expectedGeneration = generations[source] ?: 0L

        return coroutineScope.async(Dispatchers.IO + CoroutineName("Taiga UI documentation index load")) {
            val result = repository.load(source) ?: return@async null
            val published = publish(source, expectedGeneration, result.index)

            if (published && result.origin == TaigaDocsLoadOrigin.DISK_CACHE) {
                refreshLoad(source, allowCachedFallback = false)
            }

            result.index.takeIf { published }
        }
    }

    private fun refreshLoad(
        source: TaigaDocsSource,
        allowCachedFallback: Boolean,
    ): Deferred<TaigaDocsIndex?> =
        synchronized(lock) {
            pendingRefreshes[source]
                ?: createRefreshLoad(source, allowCachedFallback).also { created ->
                    pendingRefreshes[source] = created
                    created.invokeOnCompletion {
                        synchronized(lock) {
                            if (pendingRefreshes[source] === created) {
                                pendingRefreshes.remove(source)
                            }
                        }
                    }
                }
        }

    private fun createRefreshLoad(
        source: TaigaDocsSource,
        allowCachedFallback: Boolean,
    ): Deferred<TaigaDocsIndex?> {
        val expectedGeneration = generations[source] ?: 0L

        return coroutineScope.async(Dispatchers.IO + CoroutineName("Taiga UI documentation index refresh")) {
            val result =
                if (allowCachedFallback) {
                    repository.refresh(source)
                } else {
                    repository.loadFresh(source)
                }
            val index = result?.index ?: return@async null

            index.takeIf { publish(source, expectedGeneration, index) }
        }
    }

    private fun publish(
        source: TaigaDocsSource,
        expectedGeneration: Long,
        index: TaigaDocsIndex,
    ): Boolean =
        synchronized(lock) {
            if ((generations[source] ?: 0L) != expectedGeneration) {
                false
            } else {
                snapshots[source] = index
                true
            }
        }
}
