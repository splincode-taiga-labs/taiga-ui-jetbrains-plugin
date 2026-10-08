package org.taigaui.designtokens.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.nio.file.Path

class IconCatalogCacheTest {
    @Test
    fun `local catalog does not expire`() {
        var now = 1_000L
        val scopeRoot = scopeRoot("local")
        val cache = IconCatalogCache(IconCatalogClock { now })

        assertTrue(cache.publish(scopeRoot, generation = 0L, result(IconCatalogCachePolicy.LOCAL)))

        now += 24L * 60L * 60L * 1_000L

        assertTrue(requireNotNull(cache.lookup(scopeRoot)).isFresh)
    }

    @Test
    fun `successful remote catalog expires and can be refreshed`() {
        var now = 1_000L
        val scopeRoot = scopeRoot("remote-success")
        val cache = IconCatalogCache(IconCatalogClock { now })
        val maxAge = requireNotNull(IconCatalogCachePolicy.REMOTE_SUCCESS.maxAge).toMillis()

        assertTrue(cache.publish(scopeRoot, generation = 0L, result(IconCatalogCachePolicy.REMOTE_SUCCESS)))

        now += maxAge - 1L
        assertTrue(requireNotNull(cache.lookup(scopeRoot)).isFresh)

        now += 1L
        assertFalse(requireNotNull(cache.lookup(scopeRoot)).isFresh)

        assertTrue(cache.publish(scopeRoot, generation = 0L, result(IconCatalogCachePolicy.REMOTE_SUCCESS)))
        assertTrue(requireNotNull(cache.lookup(scopeRoot)).isFresh)
    }

    @Test
    fun `failed remote catalog uses short retry lifetime`() {
        var now = 1_000L
        val scopeRoot = scopeRoot("remote-retry")
        val cache = IconCatalogCache(IconCatalogClock { now })
        val maxAge = requireNotNull(IconCatalogCachePolicy.REMOTE_RETRY.maxAge).toMillis()

        assertTrue(cache.publish(scopeRoot, generation = 0L, result(IconCatalogCachePolicy.REMOTE_RETRY)))

        now += maxAge

        assertFalse(requireNotNull(cache.lookup(scopeRoot)).isFresh)
    }

    @Test
    fun `failed remote refresh retains last successful catalog until retry`() {
        var now = 1_000L
        val scopeRoot = scopeRoot("stale-if-error")
        val cache = IconCatalogCache(IconCatalogClock { now })
        val successMaxAge = requireNotNull(IconCatalogCachePolicy.REMOTE_SUCCESS.maxAge).toMillis()
        val retryMaxAge = requireNotNull(IconCatalogCachePolicy.REMOTE_RETRY.maxAge).toMillis()

        assertTrue(
            cache.publish(
                scopeRoot,
                generation = 0L,
                result(IconCatalogCachePolicy.REMOTE_SUCCESS, "@tui.fancy.success"),
            ),
        )

        now += successMaxAge
        assertFalse(requireNotNull(cache.lookup(scopeRoot)).isFresh)

        assertTrue(cache.publish(scopeRoot, generation = 0L, result(IconCatalogCachePolicy.REMOTE_RETRY)))

        val staleLookup = requireNotNull(cache.lookup(scopeRoot))

        assertEquals(listOf("@tui.fancy.success"), staleLookup.catalog.names)
        assertTrue(staleLookup.isFresh)

        now += retryMaxAge
        assertFalse(requireNotNull(cache.lookup(scopeRoot)).isFresh)

        assertTrue(
            cache.publish(
                scopeRoot,
                generation = 0L,
                result(IconCatalogCachePolicy.REMOTE_SUCCESS, "@tui.fancy.refreshed"),
            ),
        )

        val refreshedLookup = requireNotNull(cache.lookup(scopeRoot))

        assertEquals(listOf("@tui.fancy.refreshed"), refreshedLookup.catalog.names)
        assertTrue(refreshedLookup.isFresh)
    }

    @Test
    fun `invalidation rejects stale publication from previous generation`() {
        val scopeRoot = scopeRoot("generation")
        val cache = IconCatalogCache()
        val staleGeneration = cache.generation(scopeRoot)

        cache.invalidate(scopeRoot)

        assertFalse(cache.publish(scopeRoot, staleGeneration, result(IconCatalogCachePolicy.LOCAL)))
        assertNull(cache.lookup(scopeRoot))
        assertTrue(
            cache.publish(
                scopeRoot,
                cache.generation(scopeRoot),
                result(IconCatalogCachePolicy.LOCAL),
            ),
        )
    }

    @Test
    fun `invalidates only paths that can change effective icon source`() {
        val workspace = Path.of("build/fixtures/icon-invalidation").toAbsolutePath().normalize()
        val scopeRoot = workspace.resolve("node_modules/@taiga-ui")

        assertTrue(IconCatalogInvalidation.isAffected(scopeRoot, workspace.resolve("package.json")))
        assertTrue(IconCatalogInvalidation.isAffected(scopeRoot, scopeRoot.resolve("icons/package.json")))
        assertTrue(IconCatalogInvalidation.isAffected(scopeRoot, scopeRoot.resolve("icons/src/new.svg")))
        assertTrue(IconCatalogInvalidation.isAffected(scopeRoot, scopeRoot.resolve("icons/src/fancy")))
        assertTrue(IconCatalogInvalidation.isAffected(scopeRoot, scopeRoot.resolve("tds-icons/src/new.svg")))
        assertTrue(IconCatalogInvalidation.isAffected(scopeRoot, scopeRoot.resolve("proprietary")))
        assertTrue(IconCatalogInvalidation.isAffected(scopeRoot, scopeRoot.resolve("proprietary/package.json")))

        assertFalse(IconCatalogInvalidation.isAffected(scopeRoot, scopeRoot.resolve("core/styles/variables.less")))
        assertFalse(IconCatalogInvalidation.isAffected(scopeRoot, scopeRoot.resolve("icons/src/index.ts")))
        assertFalse(IconCatalogInvalidation.isAffected(scopeRoot, scopeRoot.resolve("icons/fesm2022/index.mjs")))
        assertFalse(IconCatalogInvalidation.isAffected(scopeRoot, workspace.resolve("src/app.ts")))
    }

    @Test
    fun `cache exposes scope roots invalidation result generation and clear`() {
        val first = scopeRoot("state-first")
        val second = scopeRoot("state-second")
        val cache = IconCatalogCache()

        assertNull(cache.lookup(first))
        assertEquals(emptySet<Path>(), cache.scopeRoots)
        assertEquals(0L, cache.generation(first))
        assertFalse(cache.invalidate(first))
        assertEquals(1L, cache.generation(first))

        assertTrue(
            cache.publish(
                first,
                cache.generation(first),
                result(IconCatalogCachePolicy.LOCAL, "@tui.first"),
            ),
        )
        assertTrue(
            cache.publish(
                second,
                cache.generation(second),
                result(IconCatalogCachePolicy.LOCAL, "@tui.second"),
            ),
        )
        assertEquals(setOf(first, second), cache.scopeRoots)

        assertTrue(cache.invalidate(first))
        assertFalse(cache.invalidate(first))
        assertEquals(setOf(second), cache.scopeRoots)

        cache.clear()

        assertEquals(emptySet<Path>(), cache.scopeRoots)
        assertEquals(0L, cache.generation(first))
        assertEquals(0L, cache.generation(second))
    }

    @Test
    fun `context invalidation uses explicit invalidation roots without physical scope`() {
        val root = scopeRoot("virtual-context")
        val metadata = root.resolve(".pnp.cjs")
        val packageArchive = root.resolve("cache/icons.zip")
        val context =
            IconCatalogContext(
                scopeRoot = root,
                proprietaryPackageRoot = root.resolve("proprietary"),
                publicIconsRoot = root.resolve("icons/src"),
                tdsIconsRoot = root.resolve("tds-icons/src"),
                invalidationRoots = setOf(metadata, packageArchive),
                physicalScopeRoot = null,
            )

        assertTrue(IconCatalogInvalidation.isAffected(context, metadata))
        assertTrue(IconCatalogInvalidation.isAffected(context, metadata.parent))
        assertTrue(
            IconCatalogInvalidation.isAffected(
                context,
                packageArchive.resolve("nested"),
            ),
        )
        assertFalse(
            IconCatalogInvalidation.isAffected(
                context,
                root.resolve("unrelated/file.ts"),
            ),
        )
    }

    private fun result(
        cachePolicy: IconCatalogCachePolicy,
        vararg names: String,
    ): IconCatalogLoadResult =
        IconCatalogLoadResult(
            catalog =
                IconCatalog(
                    names.map { name ->
                        IconCatalogEntry(
                            name = name,
                            svgSource =
                                IconSvgSource.Remote(
                                    URI.create(
                                        "https://example.test/" + name.removePrefix("@tui.") + ".svg",
                                    ),
                                ),
                        )
                    },
                ),
            cachePolicy = cachePolicy,
        )

    private fun scopeRoot(name: String): Path =
        Path
            .of("build/fixtures/icon-cache/$name/node_modules/@taiga-ui")
            .toAbsolutePath()
            .normalize()
}
