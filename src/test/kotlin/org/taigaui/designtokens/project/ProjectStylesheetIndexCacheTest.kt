package org.taigaui.designtokens.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.taigaui.designtokens.index.DesignTokenIndex
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProjectStylesheetIndexCacheTest {
    @Test
    fun `invalidates only project entries that depend on changed stylesheet`() {
        val workspaceRoot = Path.of("build/fixtures/project-cache").toAbsolutePath().normalize()
        val firstRequest = request(workspaceRoot, "apps/first/src/component.scss")
        val secondRequest = request(workspaceRoot, "apps/second/src/component.scss")
        val firstTheme = workspaceRoot.resolve("apps/first/src/theme.scss")
        val secondTheme = workspaceRoot.resolve("apps/second/src/theme.scss")
        val cache =
            ProjectStylesheetIndexCache { cacheRequest ->
                val dependency = if (cacheRequest == firstRequest) firstTheme else secondTheme

                buildResult(cacheRequest, setOf(dependency))
            }

        cache.getOrBuild(firstRequest)
        cache.getOrBuild(secondRequest)

        assertEquals(0, cache.invalidate(listOf(workspaceRoot.resolve("apps/other/src/theme.scss"))))
        assertTrue(cache.contains(firstRequest))
        assertTrue(cache.contains(secondRequest))

        assertEquals(1, cache.invalidate(listOf(firstTheme)))
        assertFalse(cache.contains(firstRequest))
        assertTrue(cache.contains(secondRequest))

        assertEquals(
            0,
            cache.invalidate(
                listOf(workspaceRoot.resolve("node_modules/@taiga-ui/core/styles/variables.less")),
            ),
        )
        assertTrue(cache.contains(secondRequest))
    }

    @Test
    fun `structural project changes keep broad invalidation`() {
        val workspaceRoot = Path.of("build/fixtures/project-cache-structural").toAbsolutePath().normalize()
        val firstRequest = request(workspaceRoot, "apps/first/src/component.scss")
        val secondRequest = request(workspaceRoot, "apps/second/src/component.scss")
        val cache =
            ProjectStylesheetIndexCache { cacheRequest ->
                buildResult(cacheRequest, cacheRequest.entryFiles.toSet())
            }

        cache.getOrBuild(firstRequest)
        cache.getOrBuild(secondRequest)

        assertEquals(2, cache.invalidate(listOf(workspaceRoot.resolve("apps/first/project.json"))))
        assertFalse(cache.contains(firstRequest))
        assertFalse(cache.contains(secondRequest))
    }

    @Test
    fun `related invalidation during build retries before returning stale project index`() {
        val workspaceRoot = Path.of("build/fixtures/project-cache-concurrency").toAbsolutePath().normalize()
        val request = request(workspaceRoot, "src/component.scss")
        val theme = workspaceRoot.resolve("src/theme.scss")
        val buildStarted = CountDownLatch(1)
        val releaseBuild = CountDownLatch(1)
        val builds = AtomicInteger()
        val cache =
            ProjectStylesheetIndexCache { cacheRequest ->
                builds.incrementAndGet()
                buildStarted.countDown()
                releaseBuild.await(10, TimeUnit.SECONDS)
                buildResult(cacheRequest, setOf(theme))
            }
        val executor = Executors.newSingleThreadExecutor()

        try {
            val buildFuture = executor.submit<DesignTokenIndex> { cache.getOrBuild(request) }

            assertTrue(buildStarted.await(10, TimeUnit.SECONDS))
            assertEquals(0, cache.invalidate(listOf(theme)))

            releaseBuild.countDown()
            buildFuture.get(10, TimeUnit.SECONDS)

            assertEquals(2, builds.get())
            assertTrue(cache.contains(request))
        } finally {
            releaseBuild.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `broad invalidation during build retries before publishing project index`() {
        val workspaceRoot = Path.of("build/fixtures/project-cache-broad-concurrency").toAbsolutePath().normalize()
        val request = request(workspaceRoot, "src/component.scss")
        val buildStarted = CountDownLatch(1)
        val releaseBuild = CountDownLatch(1)
        val builds = AtomicInteger()
        val cache =
            ProjectStylesheetIndexCache { cacheRequest ->
                builds.incrementAndGet()
                buildStarted.countDown()
                releaseBuild.await(10, TimeUnit.SECONDS)
                buildResult(cacheRequest, cacheRequest.entryFiles.toSet())
            }
        val executor = Executors.newSingleThreadExecutor()

        try {
            val buildFuture = executor.submit<DesignTokenIndex> { cache.getOrBuild(request) }

            assertTrue(buildStarted.await(10, TimeUnit.SECONDS))
            assertEquals(0, cache.invalidate(listOf(workspaceRoot.resolve("angular.json"))))

            releaseBuild.countDown()
            buildFuture.get(10, TimeUnit.SECONDS)

            assertEquals(2, builds.get())
            assertTrue(cache.contains(request))
        } finally {
            releaseBuild.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `unrelated invalidation during build keeps completed project index`() {
        val workspaceRoot = Path.of("build/fixtures/project-cache-unrelated-concurrency").toAbsolutePath().normalize()
        val request = request(workspaceRoot, "apps/first/src/component.scss")
        val theme = workspaceRoot.resolve("apps/first/src/theme.scss")
        val unrelatedTheme = workspaceRoot.resolve("apps/second/src/theme.scss")
        val buildStarted = CountDownLatch(1)
        val releaseBuild = CountDownLatch(1)
        val builds = AtomicInteger()
        val cache =
            ProjectStylesheetIndexCache { cacheRequest ->
                builds.incrementAndGet()
                buildStarted.countDown()
                releaseBuild.await(10, TimeUnit.SECONDS)
                buildResult(cacheRequest, setOf(theme))
            }
        val executor = Executors.newSingleThreadExecutor()

        try {
            val buildFuture = executor.submit<DesignTokenIndex> { cache.getOrBuild(request) }

            assertTrue(buildStarted.await(10, TimeUnit.SECONDS))
            assertEquals(0, cache.invalidate(listOf(unrelatedTheme)))

            releaseBuild.countDown()
            buildFuture.get(10, TimeUnit.SECONDS)

            assertEquals(1, builds.get())
            assertTrue(cache.contains(request))
        } finally {
            releaseBuild.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent callers share one project index build`() {
        val workspaceRoot = Path.of("build/fixtures/project-cache-single-flight").toAbsolutePath().normalize()
        val request = request(workspaceRoot, "src/component.scss")
        val buildStarted = CountDownLatch(1)
        val releaseBuild = CountDownLatch(1)
        val builds = AtomicInteger()
        val cache =
            ProjectStylesheetIndexCache { cacheRequest ->
                builds.incrementAndGet()
                buildStarted.countDown()
                releaseBuild.await(10, TimeUnit.SECONDS)
                buildResult(cacheRequest, cacheRequest.entryFiles.toSet())
            }
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit<DesignTokenIndex> { cache.getOrBuild(request) }

            assertTrue(buildStarted.await(10, TimeUnit.SECONDS))

            val second = executor.submit<DesignTokenIndex> { cache.getOrBuild(request) }

            releaseBuild.countDown()
            first.get(10, TimeUnit.SECONDS)
            second.get(10, TimeUnit.SECONDS)

            assertEquals(1, builds.get())
            assertTrue(cache.contains(request))
        } finally {
            releaseBuild.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `clear drops cached entries`() {
        val workspaceRoot = Path.of("build/fixtures/project-cache-clear").toAbsolutePath().normalize()
        val request = request(workspaceRoot, "src/component.scss")
        val cache =
            ProjectStylesheetIndexCache { cacheRequest ->
                buildResult(cacheRequest, emptySet())
            }

        cache.getOrBuild(request)
        assertEquals(1, cache.size)

        cache.clear()

        assertEquals(0, cache.size)
        assertFalse(cache.contains(request))
    }

    @Test
    fun `workspace ancestors and structural files trigger broad invalidation`() {
        val workspaceRoot = Path.of("build/fixtures/project-cache-broad").toAbsolutePath().normalize()
        val request = request(workspaceRoot, "src/component.scss")
        val cache =
            ProjectStylesheetIndexCache { cacheRequest ->
                buildResult(cacheRequest, emptySet())
            }

        listOf(
            workspaceRoot.parent,
            workspaceRoot.resolve("angular.json"),
            workspaceRoot.resolve("nx.json"),
            workspaceRoot.resolve("package.json"),
            workspaceRoot.resolve("apps/app"),
        ).forEach { changedPath ->
            cache.getOrBuild(request)
            assertEquals(changedPath.toString(), 1, cache.invalidate(listOf(changedPath)))
            assertFalse(changedPath.toString(), cache.contains(request))
        }
    }

    @Test
    fun `node modules changes are not treated as broad project invalidation`() {
        val workspaceRoot = Path.of("build/fixtures/project-cache-node-modules").toAbsolutePath().normalize()
        val request = request(workspaceRoot, "src/component.scss")
        val cache =
            ProjectStylesheetIndexCache { cacheRequest ->
                buildResult(cacheRequest, emptySet())
            }

        cache.getOrBuild(request)

        assertEquals(
            0,
            cache.invalidate(
                listOf(workspaceRoot.resolve("node_modules/pkg/package.json")),
            ),
        )
        assertTrue(cache.contains(request))
    }

    private fun request(
        workspaceRoot: Path,
        sourceFile: String,
    ): ProjectStylesheetIndexRequest {
        val entryFile = workspaceRoot.resolve(sourceFile)

        return ProjectStylesheetIndexRequest(
            workspaceRoot = workspaceRoot,
            projectRoot = entryFile.parent ?: workspaceRoot,
            entryFiles = listOf(entryFile),
        ).normalized()
    }

    private fun buildResult(
        request: ProjectStylesheetIndexRequest,
        dependencies: Set<Path>,
    ): ProjectStylesheetIndexBuildResult =
        ProjectStylesheetIndexBuildResult(
            index = DesignTokenIndex.build(request.workspaceRoot, emptyList()),
            dependencies = dependencies,
        )
}
