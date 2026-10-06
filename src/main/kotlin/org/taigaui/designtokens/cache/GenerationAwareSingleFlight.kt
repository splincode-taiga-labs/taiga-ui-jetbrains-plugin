package org.taigaui.designtokens.cache

import java.util.concurrent.CompletableFuture

internal enum class PendingBuildAction {
    KEEP,
    INVALIDATE,
}

internal class GenerationAwareSingleFlight<K, V, M>(
    private val metadataFactory: () -> M,
) {
    private val lock = Any()
    private val pendingBuilds = linkedMapOf<K, PendingBuild<V, M>>()
    private val generations = linkedMapOf<K, Long>()
    private var epoch = 0L

    fun getOrBuild(
        key: K,
        updateMetadata: (M) -> Unit = {},
        build: () -> V,
        isCurrent: (M, V) -> Boolean = { _, _ -> true },
        publish: (V) -> Unit = {},
    ): V {
        while (true) {
            when (
                val outcome =
                    executeOnce(
                        key = key,
                        updateMetadata = updateMetadata,
                        build = build,
                        isCurrent = isCurrent,
                        publish = publish,
                    )
            ) {
                is BuildOutcome.Success -> return outcome.value
                is BuildOutcome.Failure -> throw outcome.error
                BuildOutcome.Stale -> Unit
            }
        }
    }

    fun updatePending(update: (K, M) -> PendingBuildAction) {
        synchronized(lock) {
            val iterator = pendingBuilds.entries.iterator()

            while (iterator.hasNext()) {
                val (key, pending) = iterator.next()

                if (update(key, pending.metadata) == PendingBuildAction.INVALIDATE) {
                    generations[key] = (generations[key] ?: 0L) + 1L
                    iterator.remove()
                }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            epoch += 1L
            pendingBuilds.clear()
            generations.clear()
        }
    }

    private fun executeOnce(
        key: K,
        updateMetadata: (M) -> Unit,
        build: () -> V,
        isCurrent: (M, V) -> Boolean,
        publish: (V) -> Unit,
    ): BuildOutcome<V> {
        val access = synchronized(lock) { access(key, updateMetadata) }

        return if (access.shouldBuild) {
            buildAndComplete(key, access.pending, build, isCurrent, publish)
        } else {
            access.pending.await()
        }
    }

    private fun access(
        key: K,
        updateMetadata: (M) -> Unit,
    ): BuildAccess<V, M> {
        val generation = generationOf(key)
        val current = pendingBuilds[key]

        if (current != null && current.generation == generation) {
            updateMetadata(current.metadata)

            return BuildAccess(current, shouldBuild = false)
        }

        val created =
            PendingBuild<V, M>(
                generation = generation,
                metadata = metadataFactory(),
            )

        updateMetadata(created.metadata)
        pendingBuilds[key] = created

        return BuildAccess(created, shouldBuild = true)
    }

    private fun buildAndComplete(
        key: K,
        pending: PendingBuild<V, M>,
        build: () -> V,
        isCurrent: (M, V) -> Boolean,
        publish: (V) -> Unit,
    ): BuildOutcome<V> {
        val result = runCatching(build)
        val failure = result.exceptionOrNull()
        val outcome =
            synchronized(lock) {
                val isCurrentGeneration =
                    pendingBuilds[key] === pending && pending.generation == generationOf(key)

                when {
                    !isCurrentGeneration -> BuildOutcome.Stale
                    failure != null -> {
                        pendingBuilds.remove(key)
                        BuildOutcome.Failure(failure)
                    }
                    !isCurrent(pending.metadata, result.getOrThrow()) -> {
                        generations[key] = (generations[key] ?: 0L) + 1L
                        pendingBuilds.remove(key)
                        BuildOutcome.Stale
                    }
                    else -> {
                        val value = result.getOrThrow()
                        val publication = runCatching { publish(value) }

                        pendingBuilds.remove(key)
                        publication.fold(
                            onSuccess = { BuildOutcome.Success(value) },
                            onFailure = { error -> BuildOutcome.Failure(error) },
                        )
                    }
                }
            }

        pending.complete(outcome)

        return outcome
    }

    private fun generationOf(key: K): BuildGeneration =
        BuildGeneration(
            epoch = epoch,
            revision = generations[key] ?: 0L,
        )
}

private data class BuildGeneration(
    val epoch: Long,
    val revision: Long,
)

private class PendingBuild<V, M>(
    val generation: BuildGeneration,
    val metadata: M,
) {
    private val future = CompletableFuture<BuildOutcome<V>>()

    fun complete(outcome: BuildOutcome<V>) {
        future.complete(outcome)
    }

    fun await(): BuildOutcome<V> = future.join()
}

private data class BuildAccess<V, M>(
    val pending: PendingBuild<V, M>,
    val shouldBuild: Boolean,
)

private sealed interface BuildOutcome<out V> {
    data class Success<V>(
        val value: V,
    ) : BuildOutcome<V>

    data class Failure(
        val error: Throwable,
    ) : BuildOutcome<Nothing>

    data object Stale : BuildOutcome<Nothing>
}
