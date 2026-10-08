package org.taigaui.designtokens.documentation

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.nio.file.Files

class TaigaDocsServiceCoverageTest : BasePlatformTestCase() {
    fun testStandaloneSourceHasNoDocumentationSnapshotOrRefresh() =
        runBlocking {
            val root = Files.createTempDirectory("taiga-docs-service")

            try {
                val source = root.resolve("src/component.ts")

                Files.createDirectories(source.parent)
                Files.writeString(source, "export class Example {}")

                val service = project.service<TaigaDocsService>()

                assertNull(service.snapshotFor(source))
                assertNull(service.refresh(source))
                assertNull(service.cachedIndexFor(3))
                assertNull(service.cachedIndexFor(6))

                service.invalidate(3)
                service.invalidate(5, removeDiskCache = true)
                service.invalidate(6, removeDiskCache = true)
                service.clearMemory()
                service.warmUp(source)
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    fun testWarmUpReturnsImmediatelyForDisposedProject() {
        val disposedProject =
            Proxy.newProxyInstance(
                Project::class.java.classLoader,
                arrayOf(Project::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "isDisposed" -> true
                    "toString" -> "DisposedProject"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> null
                }
            } as Project
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            TaigaDocsService(disposedProject, scope)
                .warmUp(Files.createTempDirectory("disposed-docs").resolve("component.ts"))
        } finally {
            scope.cancel()
        }
    }
}
