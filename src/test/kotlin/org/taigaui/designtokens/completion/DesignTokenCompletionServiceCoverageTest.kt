package org.taigaui.designtokens.completion

import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.taigaui.designtokens.cache.RefreshCallback
import org.taigaui.designtokens.project.DesignTokenIndexService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class DesignTokenCompletionServiceCoverageTest : BasePlatformTestCase() {
    private lateinit var tempRoot: Path
    private lateinit var service: DesignTokenCompletionService
    private lateinit var indexService: DesignTokenIndexService

    override fun setUp() {
        super.setUp()
        tempRoot = Files.createTempDirectory("design-token-completion-service")
        service = project.service()
        indexService = project.service()
        indexService.clear()
    }

    override fun tearDown() {
        try {
            indexService.clear()
            tempRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testWarmupPublishesCatalogSupportsInspectionAndReturnsStaleSnapshot() {
        val workspace = tempRoot.resolve("workspace")
        val sourceFile = createFixture(workspace)
        val owner = Any()
        val firstUpdates = AtomicInteger()
        val firstCallback =
            RefreshCallback(
                owner = owner,
                kind = "first",
            ) {
                firstUpdates.incrementAndGet()
            }

        assertNull(service.namesFor(sourceFile, firstCallback))
        waitUntil { firstUpdates.get() == 1 }

        val names = requireNotNull(service.namesFor(sourceFile, firstCallback))

        assertTrue(TOKEN_NAME in names)
        assertTrue(indexService.isIndexCached(sourceFile))

        indexService.clear()

        val inspectionUpdates = AtomicInteger()
        assertNull(
            service.namesForInspection(
                sourceFile,
                RefreshCallback(
                    owner = owner,
                    kind = "inspection",
                    notifyWhenUnchanged = true,
                ) {
                    inspectionUpdates.incrementAndGet()
                },
            ),
        )
        waitUntil { inspectionUpdates.get() == 1 }

        assertTrue(
            TOKEN_NAME in
                requireNotNull(
                    service.namesForInspection(
                        sourceFile,
                        RefreshCallback(owner, "cached-inspection") {},
                    ),
                ),
        )

        indexService.clear()

        val stale =
            service.namesFor(
                sourceFile,
                RefreshCallback(
                    owner = owner,
                    kind = "stale",
                    notifyWhenUnchanged = true,
                ) {},
            )

        assertEquals(names, stale)
        waitUntil { indexService.isIndexCached(sourceFile) }

        val entries =
            requireNotNull(
                service.entriesFor(
                    sourceFile,
                    RefreshCallback(owner, "entries") {},
                ),
            )

        assertTrue(entries.any { entry -> entry.name == TOKEN_NAME })
    }

    fun testConcurrentCompletionRequestsShareOneWarmup() {
        val sourceFile = createFixture(tempRoot.resolve("coalesced-workspace"))
        val firstOwner = Any()
        val secondOwner = Any()
        val firstUpdates = AtomicInteger()
        val secondUpdates = AtomicInteger()

        assertNull(
            service.entriesFor(
                sourceFile,
                RefreshCallback(firstOwner, "first-warmup") {
                    firstUpdates.incrementAndGet()
                },
            ),
        )
        assertNull(
            service.entriesFor(
                sourceFile,
                RefreshCallback(secondOwner, "second-warmup") {
                    secondUpdates.incrementAndGet()
                },
            ),
        )

        waitUntil { firstUpdates.get() == 1 && secondUpdates.get() == 1 }
        assertTrue(
            requireNotNull(
                service.entriesFor(sourceFile, RefreshCallback(Any(), "cached") {}),
            ).any { entry -> entry.name == TOKEN_NAME },
        )
    }

    private fun createFixture(workspace: Path): Path {
        val sourceFile = workspace.resolve("src/app.ts")
        val packageRoot = workspace.resolve("node_modules/@taiga-ui/design-tokens")

        write(
            sourceFile,
            "const token = 'var($TOKEN_NAME)';",
        )
        write(
            packageRoot.resolve("package.json"),
            """{"name":"@taiga-ui/design-tokens","version":"5.0.0"}""",
        )
        write(
            packageRoot.resolve("palette/light.css"),
            ":root { $TOKEN_NAME: #fff; }",
        )

        return sourceFile
    }

    private fun write(
        path: Path,
        content: String,
    ) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(500) {
            UIUtil.dispatchAllInvocationEvents()

            if (condition()) {
                return
            }

            Thread.sleep(10)
        }

        assertTrue(condition())
    }

    private companion object {
        const val TOKEN_NAME = "--tui-background-base"
    }
}
