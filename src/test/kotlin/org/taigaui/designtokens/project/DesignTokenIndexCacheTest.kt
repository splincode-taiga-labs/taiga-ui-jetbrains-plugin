package org.taigaui.designtokens.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.taigaui.designtokens.index.DesignTokenIndex
import org.taigaui.designtokens.packageinfo.DesignTokensPackage
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DesignTokenIndexCacheTest {
    @Test
    fun `caches repeated requests for the same package`() {
        val builds = AtomicInteger()
        val designTokensPackage = designTokensPackage("workspace/node_modules/@taiga-ui/design-tokens")
        val cache = countingCache(builds)

        val first = cache.getOrBuild(designTokensPackage)
        val second = cache.getOrBuild(designTokensPackage)

        assertSame(first, second)
        assertEquals(1, builds.get())
        assertEquals(1, cache.size)
    }

    @Test
    fun `shares one entry between logical roots with the same real package`() {
        val builds = AtomicInteger()
        val realRoot = path("store/design-tokens-0.310.0")
        val firstPackage =
            designTokensPackage(
                root = "apps/first/node_modules/@taiga-ui/design-tokens",
                realRoot = realRoot,
            )
        val secondPackage =
            designTokensPackage(
                root = "apps/second/node_modules/@taiga-ui/design-tokens",
                realRoot = realRoot,
            )
        val cache = countingCache(builds)

        val first = cache.getOrBuild(firstPackage)
        val second = cache.getOrBuild(secondPackage)

        assertSame(first, second)
        assertEquals(1, builds.get())
        assertEquals(1, cache.size)
    }

    @Test
    fun `rebuilds when installed package version changes`() {
        val builds = AtomicInteger()
        val root = "workspace/node_modules/@taiga-ui/design-tokens"
        val cache = countingCache(builds)
        val first = cache.getOrBuild(designTokensPackage(root = root, version = "0.310.0"))
        val second = cache.getOrBuild(designTokensPackage(root = root, version = "0.311.0"))

        assertNotSame(first, second)
        assertEquals(2, builds.get())
        assertEquals(1, cache.size)
    }

    @Test
    fun `rebuilds when one logical package starts pointing to another real root`() {
        val builds = AtomicInteger()
        val logicalRoot = "workspace/node_modules/@taiga-ui/design-tokens"
        val cache = countingCache(builds)
        val first =
            cache.getOrBuild(
                designTokensPackage(
                    root = logicalRoot,
                    realRoot = path("store/first"),
                ),
            )
        val second =
            cache.getOrBuild(
                designTokensPackage(
                    root = logicalRoot,
                    realRoot = path("store/second"),
                ),
            )

        assertNotSame(first, second)
        assertEquals(2, builds.get())
        assertEquals(1, cache.size)
    }

    @Test
    fun `invalidates CSS SCSS Less and package metadata`() {
        listOf(
            "palette/light.css",
            "palette/dark.scss",
            "angular/desktop.less",
            "package.json",
        ).forEach { relativePath ->
            val builds = AtomicInteger()
            val designTokensPackage = designTokensPackage("workspace/node_modules/@taiga-ui/design-tokens")
            val cache = countingCache(builds)
            val first = cache.getOrBuild(designTokensPackage)

            assertEquals(
                1,
                cache.invalidate(listOf(designTokensPackage.realRoot.resolve(relativePath))),
            )

            val second = cache.getOrBuild(designTokensPackage)

            assertNotSame(relativePath, first, second)
            assertEquals(relativePath, 2, builds.get())
        }
    }

    @Test
    fun `invalidates directory deletion or package root replacement`() {
        val designTokensPackage = designTokensPackage("workspace/node_modules/@taiga-ui/design-tokens")

        listOf(
            designTokensPackage.realRoot.resolve("palette"),
            designTokensPackage.root,
            designTokensPackage.root.parent,
        ).forEach { changedPath ->
            val cache = countingCache(AtomicInteger())
            cache.getOrBuild(designTokensPackage)

            assertEquals(changedPath.toString(), 1, cache.invalidate(listOf(changedPath)))
            assertEquals(changedPath.toString(), 0, cache.size)
        }
    }

    @Test
    fun `does not invalidate unrelated files inside or outside package`() {
        val designTokensPackage = designTokensPackage("workspace/node_modules/@taiga-ui/design-tokens")
        val cache = countingCache(AtomicInteger())
        val index = cache.getOrBuild(designTokensPackage)

        assertEquals(
            0,
            cache.invalidate(
                listOf(
                    designTokensPackage.realRoot.resolve("README.md"),
                    designTokensPackage.realRoot.resolve("package-lock.json"),
                    path("workspace/src/app.css"),
                ),
            ),
        )
        assertSame(index, cache.getOrBuild(designTokensPackage))
    }

    @Test
    fun `invalidates only affected package in a monorepo`() {
        val builds = AtomicInteger()
        val firstPackage = designTokensPackage("apps/first/node_modules/@taiga-ui/design-tokens")
        val secondPackage = designTokensPackage("apps/second/node_modules/@taiga-ui/design-tokens")
        val cache = countingCache(builds)
        val firstIndex = cache.getOrBuild(firstPackage)
        val secondIndex = cache.getOrBuild(secondPackage)

        assertEquals(
            1,
            cache.invalidate(listOf(firstPackage.realRoot.resolve("palette/light.css"))),
        )

        assertNotSame(firstIndex, cache.getOrBuild(firstPackage))
        assertSame(secondIndex, cache.getOrBuild(secondPackage))
        assertEquals(3, builds.get())
    }

    @Test
    fun `invalidates shared real package through any logical alias`() {
        val builds = AtomicInteger()
        val realRoot = path("store/design-tokens")
        val firstPackage =
            designTokensPackage(
                root = "apps/first/node_modules/@taiga-ui/design-tokens",
                realRoot = realRoot,
            )
        val secondPackage =
            designTokensPackage(
                root = "apps/second/node_modules/@taiga-ui/design-tokens",
                realRoot = realRoot,
            )
        val cache = countingCache(builds)
        val firstIndex = cache.getOrBuild(firstPackage)
        cache.getOrBuild(secondPackage)

        assertEquals(
            1,
            cache.invalidate(listOf(secondPackage.root.resolve("palette/light.css"))),
        )

        assertNotSame(firstIndex, cache.getOrBuild(firstPackage))
        assertEquals(2, builds.get())
    }

    @Test
    fun `does not cache failed builds`() {
        val attempts = AtomicInteger()
        val designTokensPackage = designTokensPackage("workspace/node_modules/@taiga-ui/design-tokens")
        val cache =
            DesignTokenIndexCache { packageInfo ->
                if (attempts.incrementAndGet() == 1) {
                    error("broken PSI")
                }

                emptyIndex(packageInfo.realRoot)
            }

        try {
            cache.getOrBuild(designTokensPackage)
            fail("Expected the first build to fail")
        } catch (_: IllegalStateException) {
            // A failed build must leave no cache entry.
        }

        val index = cache.getOrBuild(designTokensPackage)

        assertEquals(2, attempts.get())
        assertEquals(1, cache.size)
        assertSame(index, cache.getOrBuild(designTokensPackage))
    }

    @Test
    fun `waiting caller receives original concurrent build failure`() {
        val buildStarted = CountDownLatch(1)
        val releaseBuild = CountDownLatch(1)
        val designTokensPackage = designTokensPackage("workspace/node_modules/@taiga-ui/design-tokens")
        val cache =
            DesignTokenIndexCache {
                buildStarted.countDown()
                releaseBuild.await(10, TimeUnit.SECONDS)
                error("concurrent build failed")
            }
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit<DesignTokenIndex> { cache.getOrBuild(designTokensPackage) }

            assertEquals(true, buildStarted.await(10, TimeUnit.SECONDS))

            val second = executor.submit<DesignTokenIndex> { cache.getOrBuild(designTokensPackage) }

            Thread.sleep(100)
            releaseBuild.countDown()

            val firstFailure = runCatching { first.get(10, TimeUnit.SECONDS) }.exceptionOrNull()
            val secondFailure = runCatching { second.get(10, TimeUnit.SECONDS) }.exceptionOrNull()

            assertEquals("concurrent build failed", firstFailure?.cause?.message)
            assertEquals("concurrent build failed", secondFailure?.cause?.message)
        } finally {
            releaseBuild.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `builds one index for concurrent requests`() {
        val builds = AtomicInteger()
        val buildStarted = CountDownLatch(1)
        val releaseBuild = CountDownLatch(1)
        val designTokensPackage = designTokensPackage("workspace/node_modules/@taiga-ui/design-tokens")
        val cache =
            DesignTokenIndexCache { packageInfo ->
                builds.incrementAndGet()
                buildStarted.countDown()
                releaseBuild.await(10, TimeUnit.SECONDS)
                emptyIndex(packageInfo.realRoot)
            }
        val executor = Executors.newFixedThreadPool(6)

        try {
            val futures =
                List(6) {
                    executor.submit<DesignTokenIndex> {
                        cache.getOrBuild(designTokensPackage)
                    }
                }

            assertEquals(true, buildStarted.await(10, TimeUnit.SECONDS))
            releaseBuild.countDown()

            val indexes = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, builds.get())
            indexes.drop(1).forEach { index -> assertSame(indexes.first(), index) }
        } finally {
            releaseBuild.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `clear removes cached entries and allows rebuild`() {
        val builds = AtomicInteger()
        val designTokensPackage = designTokensPackage("workspace/node_modules/@taiga-ui/design-tokens")
        val cache = countingCache(builds)

        val first = cache.getOrBuild(designTokensPackage)
        cache.clear()

        assertEquals(0, cache.size)

        val second = cache.getOrBuild(designTokensPackage)

        assertNotSame(first, second)
        assertEquals(2, builds.get())
    }

    @Test
    fun `normalizes optional workspace and source package roots`() {
        val builds = AtomicInteger()
        val root = path("workspace/node_modules/@taiga-ui/design-tokens")
        val sourcePackage =
            org.taigaui.designtokens.packageinfo.DesignTokenSourcePackage(
                name = "@taiga-ui/design-tokens",
                root = root.resolve("..").resolve("design-tokens"),
                realRoot = root,
                version = "1.0.0",
                sourceRoots = listOf(root.resolve("styles/..")),
            )
        val designTokensPackage =
            DesignTokensPackage(
                root = root,
                realRoot = root,
                version = "1.0.0",
                sourcePackages = listOf(sourcePackage),
                workspaceRoot = root.resolve("../../.."),
            )
        val cache = countingCache(builds)

        assertSame(
            cache.getOrBuild(designTokensPackage),
            cache.getOrBuild(designTokensPackage.copy()),
        )
        assertEquals(1, builds.get())
    }

    private fun countingCache(builds: AtomicInteger): DesignTokenIndexCache =
        DesignTokenIndexCache { packageInfo ->
            builds.incrementAndGet()
            emptyIndex(packageInfo.realRoot)
        }

    private fun emptyIndex(packageRoot: Path): DesignTokenIndex =
        DesignTokenIndex.build(
            packageRoot = packageRoot,
            declarations = emptyList(),
        )

    private fun designTokensPackage(
        root: String,
        realRoot: Path = path(root),
        version: String = "0.310.0",
    ): DesignTokensPackage =
        DesignTokensPackage(
            root = path(root),
            realRoot = realRoot,
            version = version,
        )

    private fun path(value: String): Path =
        Path
            .of("build", "cache-tests", value)
            .toAbsolutePath()
            .normalize()
}
