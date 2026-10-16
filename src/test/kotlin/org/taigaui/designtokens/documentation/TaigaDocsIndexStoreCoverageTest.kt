package org.taigaui.designtokens.documentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicInteger

class TaigaDocsIndexStoreCoverageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val source = requireNotNull(TaigaDocsSources.forMajor(5))

    @Test
    fun `cached index is reused refresh replaces it and clear memory drops it`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var content = docs("First")
            val repository =
                TaigaDocsRepository(
                    fetcher = TaigaDocsFetcher { content },
                    cache = TaigaDocsCache(temporaryFolder.newFolder("refresh").toPath()),
                )
            val store = TaigaDocsIndexStore(scope, repository)

            try {
                val first = requireNotNull(store.indexFor(source))

                assertSame(first, store.cached(source))
                assertSame(first, store.indexFor(source))

                content = docs("Second")
                val second = requireNotNull(store.refresh(source))

                assertEquals("Second", second.entities.single().description)
                assertSame(second, store.cached(source))

                store.clearMemory()

                assertNull(store.cached(source))
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `stale disk cached initial load publishes immediately and refreshes from remote`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val cacheRoot = temporaryFolder.newFolder("disk-refresh").toPath()
            val cache = TaigaDocsCache(cacheRoot)

            cache.write(source, docs("Cached"))
            Files.setLastModifiedTime(
                cacheRoot.resolve("${source.cacheKey}-llms-full.txt"),
                FileTime.fromMillis(0),
            )

            val fetchCalls = AtomicInteger()
            val repository =
                TaigaDocsRepository(
                    fetcher =
                        TaigaDocsFetcher {
                            fetchCalls.incrementAndGet()
                            docs("Fresh")
                        },
                    cache = cache,
                )
            val store = TaigaDocsIndexStore(scope, repository)

            try {
                val initial = requireNotNull(store.indexFor(source))

                assertEquals("Cached", initial.entities.single().description)

                withTimeout(5_000) {
                    while (
                        store
                            .cached(source)
                            ?.entities
                            ?.single()
                            ?.description != "Fresh"
                    ) {
                        delay(10)
                    }
                }

                assertEquals(1, fetchCalls.get())
                assertEquals(
                    "Fresh",
                    requireNotNull(store.cached(source))
                        .entities
                        .single()
                        .description,
                )
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `concurrent refreshes coalesce and invalidate removes memory and disk cache`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val cache = TaigaDocsCache(temporaryFolder.newFolder("coalesced-refresh").toPath())
            val calls = AtomicInteger()
            val repository =
                TaigaDocsRepository(
                    fetcher =
                        TaigaDocsFetcher {
                            calls.incrementAndGet()
                            docs("Remote")
                        },
                    cache = cache,
                )
            val store = TaigaDocsIndexStore(scope, repository)

            try {
                requireNotNull(store.indexFor(source))
                calls.set(0)

                val results =
                    List(4) {
                        async { store.refresh(source) }
                    }.awaitAll()

                assertEquals(1, calls.get())
                assertEquals(4, results.count { it != null })

                store.invalidate(source, removeDiskCache = true)

                assertNull(store.cached(source))
                assertNull(cache.read(source))
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `null repository result leaves store uncached`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val repository =
                TaigaDocsRepository(
                    fetcher = TaigaDocsFetcher { null },
                    cache = TaigaDocsCache(temporaryFolder.newFolder("missing").toPath()),
                )
            val store = TaigaDocsIndexStore(scope, repository)

            try {
                assertNull(store.indexFor(source))
                assertNull(store.refresh(source))
                assertNull(store.cached(source))
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `publish rejects data from a stale generation`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository =
            TaigaDocsRepository(
                fetcher = TaigaDocsFetcher { null },
                cache = TaigaDocsCache(temporaryFolder.newFolder("stale-generation").toPath()),
            )
        val store = TaigaDocsIndexStore(scope, repository)
        val index = requireNotNull(TaigaDocsParser().parse(source, docs("Late")))
        val publish =
            TaigaDocsIndexStore::class.java
                .getDeclaredMethod(
                    "publish",
                    TaigaDocsSource::class.java,
                    Long::class.javaPrimitiveType,
                    TaigaDocsIndex::class.java,
                ).apply { isAccessible = true }

        try {
            store.invalidate(source)

            assertFalse(publish.invoke(store, source, 0L, index) as Boolean)
            assertNull(store.cached(source))
        } finally {
            scope.cancel()
        }
    }

    private fun docs(description: String): String =
        listOf(
            "# components/Button",
            "- **Package**: `CORE`",
            description,
        ).joinToString("\n")
}
