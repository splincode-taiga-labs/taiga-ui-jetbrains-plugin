package org.taigaui.designtokens.documentation

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
internal class TaigaDocsService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    private val versionDetector = TaigaUiVersionDetector()
    private val indexStore = TaigaDocsIndexStore(coroutineScope)
    private val projectContexts = ConcurrentHashMap<Path, TaigaUiProjectContext>()

    suspend fun snapshotFor(sourceFile: Path): TaigaDocsSnapshot? {
        val context = detectContext(sourceFile)
        val source = context?.let { TaigaDocsSources.forMajor(it.majorVersion) }
        val index = source?.let { indexStore.indexFor(it) }

        return if (context != null && index != null) TaigaDocsSnapshot(context, index) else null
    }

    suspend fun refresh(sourceFile: Path): TaigaDocsSnapshot? {
        val context = detectContext(sourceFile)
        val source = context?.let { TaigaDocsSources.forMajor(it.majorVersion) }
        val index = source?.let { indexStore.refresh(it) }

        return if (context != null && index != null) TaigaDocsSnapshot(context, index) else null
    }

    fun cachedSnapshotFor(sourceFile: Path): TaigaDocsSnapshot? {
        val context = projectContexts[sourceFile.normalized()] ?: return null
        val index =
            TaigaDocsSources
                .forMajor(context.majorVersion)
                ?.let(indexStore::cached)
                ?: return null

        return TaigaDocsSnapshot(context, index)
    }

    fun warmUp(sourceFile: Path) {
        if (project.isDisposed) {
            return
        }

        coroutineScope.launch(Dispatchers.IO + CoroutineName("Taiga UI documentation warmup")) {
            snapshotFor(sourceFile)
        }
    }

    fun cachedIndexFor(majorVersion: Int): TaigaDocsIndex? =
        TaigaDocsSources.forMajor(majorVersion)?.let(indexStore::cached)

    fun invalidate(
        majorVersion: Int,
        removeDiskCache: Boolean = false,
    ) {
        projectContexts.entries.removeIf { entry -> entry.value.majorVersion == majorVersion }
        TaigaDocsSources.forMajor(majorVersion)?.let { source ->
            indexStore.invalidate(source, removeDiskCache)
        }
    }

    internal fun clearMemory() {
        projectContexts.clear()
        indexStore.clearMemory()
    }

    private suspend fun detectContext(sourceFile: Path): TaigaUiProjectContext? {
        val normalized = sourceFile.normalized()
        val context =
            withContext(Dispatchers.IO) {
                versionDetector.detect(normalized)
            }

        if (context == null) {
            projectContexts.remove(normalized)
        } else {
            projectContexts[normalized] = context
        }

        return context
    }

    private fun Path.normalized(): Path = toAbsolutePath().normalize()
}
