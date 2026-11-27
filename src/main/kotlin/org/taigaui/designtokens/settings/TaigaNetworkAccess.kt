package org.taigaui.designtokens.settings

import com.intellij.openapi.components.service
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

internal enum class TaigaNetworkMode(
    private val label: String,
) {
    ONLINE("Online"),
    CACHE_FIRST("Cache first"),
    OFFLINE_ONLY("Offline only"),
    ;

    fun allowsRequest(explicit: Boolean = false): Boolean = this == ONLINE || (this == CACHE_FIRST && explicit)

    override fun toString(): String = label
}

internal class TaigaNetworkAccess(
    private val currentMode: () -> TaigaNetworkMode,
) {
    private val lock = Any()
    private val active = mutableSetOf<FutureTask<*>>()
    private val modeChanges = MutableStateFlow(currentMode())
    val modes = modeChanges.asStateFlow()

    fun allowsRequest(explicit: Boolean = false): Boolean = synchronized(lock) { currentMode().allowsRequest(explicit) }

    fun changeMode(update: () -> Unit) {
        synchronized(lock) {
            val previous = currentMode()
            update()
            if (currentMode() != previous) {
                active.forEach { request -> request.cancel(true) }
                modeChanges.value = currentMode()
            }
        }
    }

    fun <T> request(
        explicit: Boolean = false,
        fetch: () -> T,
    ): T? {
        val task = FutureTask<T> { fetch() }

        synchronized(lock) {
            if (!currentMode().allowsRequest(explicit)) {
                return null
            }
            active.add(task)
        }

        return try {
            task.run()
            task.get()
        } catch (_: CancellationException) {
            null
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        } finally {
            synchronized(lock) { active.remove(task) }
        }
    }
}

internal fun taigaNetworkAccess(): TaigaNetworkAccess = service<TaigaDesignTokensSettings>().networkAccess
